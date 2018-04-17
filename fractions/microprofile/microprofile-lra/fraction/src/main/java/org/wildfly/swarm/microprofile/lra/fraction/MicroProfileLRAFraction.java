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

package org.wildfly.swarm.microprofile.lra.fraction;

import org.wildfly.swarm.spi.api.Fraction;
import org.wildfly.swarm.spi.api.Module;
import org.wildfly.swarm.spi.api.annotations.DeploymentModule;

@DeploymentModule(name = "javax.ws.rs.api")
@DeploymentModule(name = "org.jboss.dmr")
@DeploymentModule(name = "org.wildfly.swarm.microprofile.lra.fraction",
        export = true, slot = "deployment", metaInf = DeploymentModule.MetaInfDisposition.IMPORT)
@DeploymentModule(name = "org.eclipse.microprofile.lra")
@DeploymentModule(name = "org.eclipse.microprofile.lra",
        export = true, services = Module.ServiceHandling.IMPORT, slot = "main", metaInf = DeploymentModule.MetaInfDisposition.IMPORT)
@DeploymentModule(name = "org.jboss.narayana.rts.lra-client", export = true)
public class MicroProfileLRAFraction implements Fraction<MicroProfileLRAFraction> {

    public MicroProfileLRAFraction() {
    }
}
