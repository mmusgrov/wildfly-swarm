/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.swarm.microprofile.lra.fraction.runtime;

import io.narayana.lra.client.GenericLRAExceptionMapper;
import io.narayana.lra.client.IllegalLRAStateExceptionMapper;
import io.narayana.lra.client.InvalidLRAIdExceptionMapper;
import io.narayana.lra.client.ParentLRAJoinExceptionMapper;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.wildfly.swarm.microprofile.lra.fraction.MicroProfileLRAFraction;
import org.wildfly.swarm.spi.api.DeploymentProcessor;
import org.wildfly.swarm.spi.runtime.annotations.DeploymentScoped;
import org.wildfly.swarm.undertow.WARArchive;
import org.wildfly.swarm.undertow.descriptors.WebXmlAsset;

import javax.inject.Inject;

@DeploymentScoped
public class LraInstaller implements DeploymentProcessor {
    private static final Logger LOGGER = Logger.getLogger("org.wildfly.swarm.microprofile.lra");
    private final Archive<?> archive;

    @Inject
    private MicroProfileLRAFraction fraction;

    @Inject
    public LraInstaller(Archive archive) {
        this.archive = archive;
    }

    @Override
    public void process() throws Exception {
        LOGGER.info("Determining whether to install LRA integration or not.");
        if (archive.getName().endsWith(".war")) {
            LOGGER.logf(Logger.Level.INFO, "Installing the LRA integration for the deployment %s", archive.getName());
            WARArchive webArchive = archive.as(WARArchive.class);
            WebXmlAsset webXml = webArchive.findWebXmlAsset();

            String providers = String.format("%s,%s,%s",
                    GenericLRAExceptionMapper.class.getName(),
                    IllegalLRAStateExceptionMapper.class.getName(),
                    InvalidLRAIdExceptionMapper.class.getName(),
                    ParentLRAJoinExceptionMapper.class.getName()
            );

            if (webXml == null) {
                LOGGER.warnf("Deployment does not containa webXml asset, will not register any JAX-RS exception mappers");
            } else {
                webXml.setContextParam("resteasy.providers", providers);
            }
        }
    }
}
