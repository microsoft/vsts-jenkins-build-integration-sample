// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.logging.Logger;

import com.microsoft.teamfoundation.build.webapi.model.Build;
import com.microsoft.teamfoundation.build.webapi.model.BuildResult;
import com.microsoft.teamfoundation.build.webapi.model.BuildStatus;
import com.microsoft.teamfoundation.distributedtask.webapi.TaskHttpClient;
import com.microsoft.teamfoundation.distributedtask.webapi.model.TaskLog;
import com.microsoft.teamfoundation.distributedtask.webapi.model.TaskOrchestrationPlan;
import com.microsoft.teamfoundation.distributedtask.webapi.model.TaskResult;
import com.microsoft.teamfoundation.distributedtask.webapi.model.TimelineRecord;
import com.microsoft.teamfoundation.distributedtask.webapi.model.TimelineRecordState;
import com.microsoft.teamfoundation.distributedtask.webapi.model.TimelineReference;
import com.microsoft.tfs.plugin.TfsBuildFacade;

import hudson.model.AbstractBuild;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.model.Result;

/**
 * This class is a facade to update TFS build from Jenkins.
 *
 * All updates to TFS build should go through this class.  Also deliberately this
 * class only contains IDs and does not keep state.  All build update operation are PATCH
 * based, and this object maybe out of sync with what is really happening on TFS,
 * so we GET the object from the IDs and then PATCH the server
 */
public class TfsBuildFacadeImpl implements TfsBuildFacade {

    private static final Logger logger = Logger.getLogger(TfsBuildFacadeImpl.class.getName());

    /*
     * Constants
     */
    private static final String JOB_RECORD_TYPE = "Job";
    private static final String JOB_RECORD_NAME = "Build";
    private static final String JENKINS_RECORD_TYPE = "Task";
    private static final String JENKINS_WORKER_NAME = "Jenkins";

    /*
     * The Jenkins build which is running
     */
    private AbstractBuild jenkinsBuild;

    /*
     * The ID of the build container running on TFS
     */
    private int tfsBuildId;

    /*
     * The ID of the orchestration plan for this jenkins build on TFS
     */
    private UUID planId;

    /*
     * The ID of the Team Project for this jenkins build on TFS
     */
    private UUID projectId;

    /*
     * The ID of the timeline for this jenkins build on TFS
     */
    private UUID timelineId;

    /*
     * The ID of the job record
     */
    private UUID jobRecordId;

    /*
     * The job log id for the job record
     */
    private int jobLogId;

    /*
     * The job log id for the jenkins task record
     */
    private int jenkinsLogId;

    /*
     * The name of the currently executed Jenkins Task
     */
    private String jenkinsTaskName;

    /*
     * The TFS REST client
     */
    private TfsClient client;

    /* should only be instantiated from TfsBuildFacadeFactoryImpl from same package */
    /* default */
    public TfsBuildFacadeImpl(final Build tfsBuild, final AbstractBuild jenkinsBuild, final TfsClient tfsClient) {

        this.tfsBuildId = tfsBuild.getId();
        this.jenkinsBuild = jenkinsBuild;
        this.client = tfsClient;
        this.planId = tfsBuild.getOrchestrationPlan().getPlanId();
        this.projectId = tfsBuild.getProject().getId();

        TaskOrchestrationPlan plan = getTaskClient().getPlan(projectId, "build", planId);

        this.timelineId = plan.getTimeline().getId();

        // populate timeline record
        List<TimelineRecord> records = queryTfsTimelineRecords(getTimelineId());
        if (records == null) {
            records = new ArrayList<TimelineRecord>();
        }

        TimelineRecord jobRecord = null;
        TimelineRecord jenkinsTaskRecord = null;

        for (TimelineRecord record : records) {
            if (record.getType().equalsIgnoreCase(JOB_RECORD_TYPE)) {
                jobRecord = record;
            } else if (record.getType().equalsIgnoreCase(JENKINS_RECORD_TYPE)) {
                jenkinsTaskRecord = record;
            }
        }

        if (jobRecord == null) {
            jobRecord = createTimelineJobRecord();
            records.add(jobRecord);
        }

        if (jenkinsTaskRecord == null) {
            jenkinsTaskRecord = createTimelineJenkinsTaskRecord(jobRecord, 1);
            records.add(jenkinsTaskRecord);
        }

        String jobRecordName = "Jenkins Build";
        jobRecord.setName(jobRecordName);
        createLogForTimelineRecord(jobRecord);

        String jenkinsRecordName = jenkinsBuild.getFullDisplayName();
        jenkinsTaskRecord.setName(jenkinsRecordName);
        createLogForTimelineRecord(jenkinsTaskRecord);

        updateRecords(records, timelineId);

        // populate rest of the fields
        this.jobLogId = jobRecord.getLog().getId();;
        this.jenkinsLogId = jenkinsTaskRecord.getLog().getId();
        this.jobRecordId = jobRecord.getId();
        this.jenkinsTaskName = jenkinsRecordName;
    }

    /**
     * Get the build container ID on TFS
     *
     * @return tfs build id
     */
    public int getTfsBuildId() {
        return tfsBuildId;
    }

    /**
     * Update TFS Build status to started with starting time
     */
    public void startBuild() {
        Build b = queryTfsBuild();

        b.setStartTime(new Date());
        b.setStatus(BuildStatus.IN_PROGRESS);

        getClient().getBuildClient().updateBuild(b, b.getProject().getId(), b.getId());
    }

    /**
     * Update TFS Build status to finished with Jenkins status
     */
    public void finishBuild() {
        Build b = queryTfsBuild();
        b.setFinishTime(new Date());

        AbstractBuild jenkinsBuild = getJenkinsBuild();
        BuildResult tfsResult = convertToTfsBuildResult(jenkinsBuild.getResult());

        b.setResult(tfsResult);
        b.setStatus(BuildStatus.COMPLETED);

        String commitSha1 =  getSourceCommit();
        logger.info("Setting TFS build sourceVersion to: " + commitSha1);
        b.setSourceVersion(commitSha1);

        getClient().getBuildClient().updateBuild(b, b.getProject().getId(), b.getId());
    }

    /**
     * Update all tasks' status to inProgress, this is because we only have one
     * Jenkins task at the moment.
     */
    public void startAllTaskRecords() {
        List<TimelineRecord> records = queryTfsTimelineRecords(getTimelineId());
        Date startTime = new Date();

        for (TimelineRecord record : records) {
            record.setState(TimelineRecordState.IN_PROGRESS);
            record.setStartTime(startTime);
            record.setWorkerName(JENKINS_WORKER_NAME);
        }

        updateRecords(records, getTimelineId());
    }

    /**
     * Update all tasks' status to Jenkins's build status, this is because we only have one
     * Jenkins task at the moment.
     */
    public void finishAllTaskRecords() {
        List<TimelineRecord> records = queryTfsTimelineRecords(getTimelineId());
        TaskResult result = convertToTfsTaskResult(getJenkinsBuild().getResult());
        Date finishTime = new Date();

        for (TimelineRecord record : records) {
            record.setState(TimelineRecordState.COMPLETED);
            record.setFinishTime(finishTime);
            record.setResult(result);

            TimelineReference detailsRef = record.getDetails();

            if (detailsRef != null) {
                List<TimelineRecord> detailRecords = queryTfsTimelineRecords(detailsRef.getId());

                if (detailRecords != null) {
                    for (TimelineRecord detailRecord : detailRecords) {
                        if (detailRecord.getState() == TimelineRecordState.IN_PROGRESS) {
                            detailRecord.setState(TimelineRecordState.COMPLETED);
                            detailRecord.setFinishTime(finishTime);
                            detailRecord.setResult(result);
                        }
                    }

                    updateRecords(detailRecords, detailsRef.getId());
                }
            }
        }

        updateRecords(records, getTimelineId());
    }

    /**
     * Posting lines to TFS build console
     *
     * The console feed and log appears to be the same due to we have only one task (jenkins) in this build container
     *
     * @param lines
     */
    public void appendJobLog(List<String> lines) {
        if (lines == null || lines.size() == 0) {
            return;
        }

        // post console feed
        getTaskClient().postLines(getProjectId(), "build", lines, getPlanId(), getTimelineId(), getJobRecordId());

        // append the feed to Jenkins Task log
        try {
            getTaskClient().appendLog(getByteArrayInputStream(lines), getProjectId(), "build", getPlanId(), getJenkinsLogId());
        } catch (IOException e) {
            logger.severe("Failed to send log to Microsoft TFS: "+e.getMessage());
        }

        List<String> jobLogLines = new ArrayList();
        for (String line : lines) {
            jobLogLines.add("[" + jenkinsTaskName + "] " + line);
        }

        // append the feed to Job log
        try {
            getTaskClient().appendLog(getByteArrayInputStream(jobLogLines), getProjectId(), "build", getPlanId(), getJobLogId());
        } catch (IOException e) {
            logger.severe("Failed to send log to Microsoft TFS: "+e.getMessage());
        }
        //
    }

    private InputStream getByteArrayInputStream(List<String> lines) throws IOException {
        // assuming each line is 256-bytes long to avoid grow constantly
        ByteArrayOutputStream os = new ByteArrayOutputStream(lines.size() * 256);
        byte[] newLine = String.format("%n").getBytes(Charset.defaultCharset());
        for (String line : lines) {
            os.write(line.getBytes(Charset.defaultCharset()));
            os.write(newLine);
        }

        return new ByteArrayInputStream(os.toByteArray());
    }

    private Build queryTfsBuild() {
        return getClient().getBuildClient().getBuild(getTfsBuildId(), null);
    }

    private List<TimelineRecord> queryTfsTimelineRecords(UUID timelineId) {
        return getTaskClient().getRecords(getProjectId(), "build", getPlanId(), timelineId);
    }

    /**
     * Create a log reference for the job record
     *
     * @return jobId
     */
    private void createLogForTimelineRecord(TimelineRecord record) {

            TaskLog log = createTfsLog("logs\\" + record.getId().toString());
            logger.info("Setting up record " + record.getType() + " log path: " + log.getPath() + ", log id: " + log.getId());
            record.setLog(log);
    }

    private TaskLog createTfsLog(String path) {
        TaskLog log = new TaskLog();
        log.setPath(path);

        // Note that we should use the TaskLog object returned from the server,
        // but not that we passed as the parameter.
        return getTaskClient().createLog(getProjectId(), "build", log, getPlanId());
    }

    private void updateRecords(List<TimelineRecord> timelineRecords, UUID timelineId) {
        getTaskClient().updateRecords(getProjectId(), "build", timelineRecords, getPlanId(), timelineId);
    }

    private TimelineRecord createTimelineJobRecord() {
        TimelineRecord jobRecord = new TimelineRecord();
        jobRecord.setId(UUID.randomUUID());
        jobRecord.setType(JOB_RECORD_TYPE);
        jobRecord.setState(TimelineRecordState.PENDING);

        return jobRecord;
    }

    private TimelineRecord createTimelineJenkinsTaskRecord(TimelineRecord jobRecord, int orderNumber) {
        TimelineRecord jenkinsTaskRecord;

        jenkinsTaskRecord = new TimelineRecord();
        jenkinsTaskRecord.setId(UUID.randomUUID());
        jenkinsTaskRecord.setType(JENKINS_RECORD_TYPE);
        jenkinsTaskRecord.setParentId(jobRecord.getId());
        jenkinsTaskRecord.setOrder(orderNumber);
        jenkinsTaskRecord.setState(TimelineRecordState.PENDING);

        return jenkinsTaskRecord;
    }

    private String getSourceCommit() {
        String sourceVersion = getGitSourceCommit();
        if (sourceVersion != null) {
            return sourceVersion;
        }

        // could add action to get other SCM revision string here
        return "undetermined";
    }

    private String getGitSourceCommit() {
        // depend on git plugin
        for (BuildData data : getJenkinsBuild().getActions(BuildData.class)) {
            Revision revision = data.getLastBuiltRevision();
            if (revision != null) {
                return revision.getSha1String();
            }
        }

        return null;
    }

    private BuildResult convertToTfsBuildResult(Result jenkinsResult) {
        if (jenkinsResult == Result.SUCCESS) {
            return BuildResult.SUCCEEDED;
        }

        if (jenkinsBuild.getResult() == Result.ABORTED) {
            return BuildResult.CANCELED;
        }

        // Assume FAILURE (and other cases that aren't successful)
        return BuildResult.FAILED;
    }

    private TaskResult convertToTfsTaskResult(Result jenkinsResult) {
        if (jenkinsResult == Result.SUCCESS) {
            return TaskResult.SUCCEEDED;
        }

        if (jenkinsBuild.getResult() == Result.ABORTED) {
            return TaskResult.CANCELED;
        }

        // Assume FAILURE (and other cases that aren't successful)
        return TaskResult.FAILED;
    }

    private boolean hasLogRecord() {
        return getJobLogId() != -1;
    }

    private UUID getPlanId() {
        return planId;
    }

    private UUID getProjectId() {
        return projectId;
    }

    private UUID getTimelineId() {
        return timelineId;
    }

    private UUID getJobRecordId() {
        return jobRecordId;
    }

    private int getJobLogId() {
        return jobLogId;
    }

    private int getJenkinsLogId() {
        return jenkinsLogId;
    }

    private AbstractBuild getJenkinsBuild() {
        return jenkinsBuild;
    }

    private TfsClient getClient() { return client; }

    private TaskHttpClient getTaskClient() {
        return getClient().getTaskHttpClient();
    }
}
