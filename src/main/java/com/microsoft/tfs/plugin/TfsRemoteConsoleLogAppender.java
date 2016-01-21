// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin;

import hudson.console.ConsoleNote;
import hudson.console.LineTransformationOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Created by yacao on 12/31/2014.
 */
public class TfsRemoteConsoleLogAppender extends LineTransformationOutputStream {

    private static final Logger logger = Logger.getLogger(TfsRemoteConsoleLogAppender.class.getName());

    private final OutputStream delegate;

    private final TfsBuildFacade tfsBuildFacade;
    private final ScheduledExecutorService executorService;
    private final BlockingQueue<String> logs;

    public TfsRemoteConsoleLogAppender(OutputStream delegate, TfsBuildFacade tfsBuildFacade) {
        this.delegate = delegate;
        this.logs = new LinkedBlockingQueue<String>();

        // single thread for posting log to guarantee order
        this.executorService = Executors.newScheduledThreadPool(1);

        this.tfsBuildFacade = tfsBuildFacade;

        logger.info("Initialized Tfs Remote Console log appender");
    }

    @Override
    protected void eol(byte[] b, int len) throws IOException {
        delegate.write(b, 0, len);

        String line = ConsoleNote.removeNotes(new String(b, 0, len, Charset.defaultCharset())).trim();
        if (!logs.offer(line)) {
            logger.warning(String.format("Failed to add log line: %s to queue, is the logger rolling too fast?", line));
        }
    }

    public void flush() throws IOException {
        delegate.flush();
    }

    public void close() throws IOException {
        delegate.close();
        executorService.shutdown();

        try {
            if (this.executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.info("Thread pool has terminated.");

                if (!logs.isEmpty()) {
                    logger.info(String.format("Append %d remaining logs.", logs.size()));

                    List<String> lines = new ArrayList<String>(logs.size());
                    logs.drainTo(lines);
                    tfsBuildFacade.appendJobLog(lines);
                }

            } else {
                logger.warning("Log appender took more than 30 seconds to complete, log maybe incomplete on remote console.");
            }

        } catch (InterruptedException e) {
            logger.warning("Console log appender interrupted, log maybe incomplete on remote console.");
        }
    }

    public void start() {
        final Runnable logAppender = new Runnable() {

            public void run() {
                List<String> lines = new ArrayList<String>(100);

                String line;
                while ((line = logs.poll()) != null) {
                    lines.add(line);

                    if (lines.size() >= 100) {
                        tfsBuildFacade.appendJobLog(lines);
                        lines.clear();
                    }
                }

                if (!lines.isEmpty()) {
                    tfsBuildFacade.appendJobLog(lines);
                }
            }
        };

        logger.info("TFS remote console log appender started");
        executorService.scheduleWithFixedDelay(logAppender, 1, 1, TimeUnit.SECONDS);
    }
}
