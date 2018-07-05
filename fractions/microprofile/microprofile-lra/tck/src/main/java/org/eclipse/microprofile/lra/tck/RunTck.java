/*
 *******************************************************************************
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.eclipse.microprofile.lra.tck;

import org.eclipse.microprofile.lra.client.LRAClient;

import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RunTck implements ServletContextListener {
    private static final String RUN_TCK_PROP = "lra.tck.run";
    private static final String EXIT_AFTER_TCK_PROP = "lra.tck.exit";
    private static final long DELAY_TCK_RUN = 5L; // wait for all resource to deploy

    private ScheduledFuture<?> timer;
    private ScheduledExecutorService scheduler;

    @Inject
    private LRAClient lraClient;

    @Override
    public void contextInitialized(ServletContextEvent sce) {

        if (Boolean.getBoolean(RUN_TCK_PROP)) {
            System.out.printf("Waiting %d seconds for the TCK to deploy ...%n", DELAY_TCK_RUN);

            scheduler = Executors.newScheduledThreadPool(1);

            timer = scheduler.schedule((Runnable) this::runTck, DELAY_TCK_RUN, TimeUnit.SECONDS);
        }
    }

    private void runTck() {
        TckTests.beforeClass(lraClient);
        TckTests test = new TckTests();

        test.before();

        TckResult results = test.runTck(lraClient, "all", false);

        test.after();

        List<String> failures = results.getFailures();
        int exitStatus = 0;

        if (failures.size() != 0) {
            System.out.printf("There were TCK failures:%n");

            failures.forEach(f -> System.out.printf("%s%n", f));

            exitStatus = 1;
        }

        if (Boolean.getBoolean(EXIT_AFTER_TCK_PROP)) {
            System.exit(exitStatus);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (timer != null) {
            timer.cancel(true);
            timer = null;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }
}
