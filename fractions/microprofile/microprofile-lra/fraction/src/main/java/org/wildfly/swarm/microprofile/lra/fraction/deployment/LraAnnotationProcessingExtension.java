/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat Middleware LLC, and individual contributors
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

package org.wildfly.swarm.microprofile.lra.fraction.deployment;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.WithAnnotations;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.container.Suspended;

import io.narayana.lra.client.NarayanaLRAClient;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.LRA;
import org.eclipse.microprofile.lra.annotation.Leave;
import org.eclipse.microprofile.lra.annotation.Status;

import org.eclipse.microprofile.lra.client.LRAClient;
import org.jboss.logging.Logger;

import static org.eclipse.microprofile.lra.client.LRAClient.LRA_COORDINATOR_HOST_KEY;
import static org.eclipse.microprofile.lra.client.LRAClient.LRA_COORDINATOR_PATH_KEY;
import static org.eclipse.microprofile.lra.client.LRAClient.LRA_COORDINATOR_PORT_KEY;
import static org.eclipse.microprofile.lra.client.LRAClient.LRA_RECOVERY_HOST_KEY;
import static org.eclipse.microprofile.lra.client.LRAClient.LRA_RECOVERY_PATH_KEY;
import static org.eclipse.microprofile.lra.client.LRAClient.LRA_RECOVERY_PORT_KEY;

/**
 * <p>
 *     Scan a deployment looking for LRA annotations.
 * <p>
 * When added at the class path of the project this extension validates
 * if the classes contain compulsory annotation complementary to {@link LRA}.
 * The rules of what are compulsory annotations and their attributes
 * are defined in LRA specification.
 * <p>
 * Failures are reported as warnings.
 *
 * @author Ondra Chaloupka <ochaloup@redhat.com>
 */
public class LraAnnotationProcessingExtension implements Extension {
    private static final Logger LOGGER = Logger.getLogger("org.wildfly.swarm.microprofile.lra");

    private List<String> failureCatalog = Collections.synchronizedList(new ArrayList<>());
    private boolean isParticipant;

    <X> void processLraAnnotatedType(@Observes @WithAnnotations({LRA.class}) ProcessAnnotatedType<X> classAnnotatedWithLra) {
        isParticipant = true;

        // All compulsory LRA annotations are available at the class
        Supplier<Stream<AnnotatedMethod<? super X>>> sup = () -> classAnnotatedWithLra.getAnnotatedType().getMethods().stream();
        Set<Class<? extends Annotation>> missing = new HashSet<>();
        if (!sup.get().anyMatch(m -> m.isAnnotationPresent(Compensate.class))) {
            missing.add(Compensate.class);
        }

        // gathering all LRA annotations in the class
        List<LRA> lraAnnotations = new ArrayList<>();
        LRA classLraAnnotation = classAnnotatedWithLra.getAnnotatedType().getAnnotation(LRA.class);
        if (classLraAnnotation != null) {
            lraAnnotations.add(classLraAnnotation);
        }
        List<LRA> methodlraAnnotations = sup.get()
                .filter(m -> m.isAnnotationPresent(LRA.class))
                .map(m -> m.getAnnotation(LRA.class))
                .collect(Collectors.toList());
        lraAnnotations.addAll(methodlraAnnotations);

        // when LRA annotations expect no context then they are not part of the LRA and no handling
        // of the completion or compensation is needed
        boolean isNoLRAContext = lraAnnotations.stream().allMatch(
                lraAnn -> (lraAnn.value() == LRA.Type.NEVER || lraAnn.value() == LRA.Type.NOT_SUPPORTED));
        if (isNoLRAContext) {
            return;
        }

        // if any of the LRA annotations have set the join attribute to true (join to be true means
        // handling it as full LRA participant which needs to be completed, compensated...)
        // then have to process checks for compulsory LRA annotations
        boolean isJoin = lraAnnotations.stream().anyMatch(lraAnn -> lraAnn.join());

        final String classAnnotatedWithLraName = classAnnotatedWithLra.getAnnotatedType().getJavaClass().getName();
        if (!missing.isEmpty() && isJoin) {
            failureCatalog.add(String.format("Class %s is annotated with %s but is missing the following required annotations %s",
                    classAnnotatedWithLraName,
                    LRA.class.getName(), missing.toString()));
            }

        // Only one of each LRA annotation is placed in the class
        List<AnnotatedMethod<? super X>> methodsWithCompensate = sup.get()
            .filter(m -> m.isAnnotationPresent(Compensate.class))
            .collect(Collectors.toList());
        List<AnnotatedMethod<? super X>> methodsWithComplete = sup.get()
            .filter(m -> m.isAnnotationPresent(Complete.class))
            .collect(Collectors.toList());
        List<AnnotatedMethod<? super X>> methodsWithStatus = sup.get()
            .filter(m -> m.isAnnotationPresent(Status.class))
            .collect(Collectors.toList());
        List<AnnotatedMethod<? super X>> methodsWithLeave = sup.get()
            .filter(m -> m.isAnnotationPresent(Leave.class))
            .collect(Collectors.toList());
        List<AnnotatedMethod<? super X>> methodsWithForget = sup.get()
            .filter(m -> m.isAnnotationPresent(Forget.class))
            .collect(Collectors.toList());

        BiFunction<Class<?>, List<AnnotatedMethod<? super X>>, String> errorMsg = (clazz, methods) -> String.format(
            "Multiple annotations of type '%s' are used in the class '%s' on the methods %s. Only one per the class is expected.",
            clazz.getName(), classAnnotatedWithLraName,
            methods.stream().map(a -> a.getJavaMember().getName()).collect(Collectors.toList()));
        if (methodsWithCompensate.size() > 1) {
            failureCatalog.add(errorMsg.apply(Compensate.class, methodsWithCompensate));
        }
        if (methodsWithComplete.size() > 1) {
            failureCatalog.add(errorMsg.apply(Complete.class, methodsWithComplete));
        }
        if (methodsWithStatus.size() > 1) {
            failureCatalog.add(errorMsg.apply(Status.class, methodsWithStatus));
        }
        if (methodsWithLeave.size() > 1) {
            failureCatalog.add(errorMsg.apply(Leave.class, methodsWithLeave));
        }
        if (methodsWithForget.size() > 1) {
            failureCatalog.add(errorMsg.apply(Forget.class, methodsWithForget));
        }

        if (methodsWithCompensate.size() > 0) {
            // Each method annotated with LRA-style annotations contain all necessary REST annotations
            // @Compensate - requires @Path and @PUT
            final AnnotatedMethod<? super X> methodWithCompensate = methodsWithCompensate.get(0);
            Function<Class<?>, String> getCompensateMissingErrMsg = (wrongAnnotation) ->
                getMissingAnnotationError(methodWithCompensate, classAnnotatedWithLra,
                        Compensate.class, wrongAnnotation);
            boolean isCompensateContainsPathAnnotation = methodWithCompensate.getAnnotations().stream()
                    .anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isCompensateContainsPathAnnotation) {
                failureCatalog.add(getCompensateMissingErrMsg.apply(Path.class));
            }
            boolean isCompensateContainsPutAnnotation = methodWithCompensate.getAnnotations().stream()
                    .anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isCompensateContainsPutAnnotation) {
                failureCatalog.add(getCompensateMissingErrMsg.apply(PUT.class));
            }
            boolean isCompensateParametersContainsSuspended = methodWithCompensate.getParameters().stream()
                    .flatMap(p -> p.getAnnotations().stream())
                    .anyMatch(a -> a.annotationType().equals(Suspended.class));
            if (isCompensateParametersContainsSuspended) {
                if (methodsWithStatus.size() == 0 || methodsWithForget.size() == 0) {
                    failureCatalog.add(getMissingAnnotationsForAsynchHandling(
                            methodWithCompensate, classAnnotatedWithLra, Compensate.class));
                }
            }
        }

        if (methodsWithComplete.size() > 0) {
            // @Complete - requires @Path and @PUT
            final AnnotatedMethod<? super X> methodWithComplete = methodsWithComplete.get(0);
            Function<Class<?>, String> getCompleteMissingErrMsg = (wrongAnnotation) ->
                getMissingAnnotationError(methodWithComplete, classAnnotatedWithLra, Complete.class, wrongAnnotation);
            boolean isCompleteContainsPathAnnotation = methodWithComplete.getAnnotations().stream()
                    .anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isCompleteContainsPathAnnotation) {
                failureCatalog.add(getCompleteMissingErrMsg.apply(Path.class));
            }
            boolean isCompleteContainsPutAnnotation = methodWithComplete.getAnnotations().stream()
                    .anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isCompleteContainsPutAnnotation) {
                failureCatalog.add(getCompleteMissingErrMsg.apply(PUT.class));
            }
            boolean isCompleteParametersContainsSuspended = methodWithComplete.getParameters().stream()
                    .flatMap(p -> p.getAnnotations().stream())
                    .anyMatch(a -> a.annotationType().equals(Suspended.class));
            if (isCompleteParametersContainsSuspended) {
                if (methodsWithStatus.size() == 0 || methodsWithForget.size() == 0) {
                    failureCatalog.add(getMissingAnnotationsForAsynchHandling(
                            methodWithComplete, classAnnotatedWithLra, Complete.class));
                }
            }
        }

        if (methodsWithStatus.size() > 0) {
            // @Status - requires @Path and @GET
            final AnnotatedMethod<? super X> methodWithStatus = methodsWithStatus.get(0);
            Function<Class<?>, String> getStatusMissingErrMsg = (wrongAnnotation) ->
                getMissingAnnotationError(methodWithStatus, classAnnotatedWithLra, Status.class, wrongAnnotation);
            boolean isStatusContainsPathAnnotation = methodWithStatus.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isStatusContainsPathAnnotation) {
                failureCatalog.add(getStatusMissingErrMsg.apply(Path.class));
            }
            boolean isStatusContainsGetAnnotation = methodWithStatus.getAnnotations().stream()
                    .anyMatch(a -> a.annotationType().equals(GET.class));
            if (!isStatusContainsGetAnnotation) {
                failureCatalog.add(getStatusMissingErrMsg.apply(GET.class));
            }
        }

        if (methodsWithLeave.size() > 0) {
            // @Leave - requires @PUT
            final AnnotatedMethod<? super X> methodWithLeave = methodsWithLeave.get(0);
            boolean isLeaveContainsPutAnnotation = methodWithLeave.getAnnotations().stream()
                    .anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isLeaveContainsPutAnnotation) {
                failureCatalog.add(getMissingAnnotationError(
                        methodWithLeave, classAnnotatedWithLra, Leave.class, PUT.class));
            }
        }

        if (methodsWithForget.size() > 0) {
            // @Forget - requires @DELETE
            final AnnotatedMethod<? super X> methodWithForget = methodsWithForget.get(0);
            boolean isForgetContainsPutAnnotation = methodWithForget.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(DELETE.class));
            if (!isForgetContainsPutAnnotation) {
                failureCatalog.add(getMissingAnnotationError(methodWithForget, classAnnotatedWithLra, Forget.class, DELETE.class));
            }
        }
    }

    private static final int COORDINATOR_SWARM_PORT = 8080;


    private boolean initLraClient;

    /**
     * Handle LRAClient injection types.
     *
     * @param pip - the injection point event information
     */
    void processLRAClientInjections(@Observes ProcessInjectionPoint pip) {
        LOGGER.debugf("pipRaw: %s", pip.getInjectionPoint());
        InjectionPoint ip = pip.getInjectionPoint();
        String baseType = ip.getAnnotated().getBaseType().getTypeName();

        if (LRAClient.class.getCanonicalName().equals(baseType)) {
            initLraClient = true;
        }
    }

        /**
         * Called when the deployment is deployed.
         */
    private void afterDeploymentValidation(@Observes final AfterDeploymentValidation abd, BeanManager beanManager) {
        if (isParticipant) {
            LOGGER.infof(LraAnnotationProcessingExtension.class.getName() + " extension validating deployment");

            failureCatalog.forEach(error -> LOGGER.warnf("%s", error));

            failureCatalog.clear();
        }

        Config config = ConfigProvider.getConfig();

        String lcHost = config.getOptionalValue(LRA_COORDINATOR_HOST_KEY, String.class).orElse("localhost");
        int lcPort = config.getOptionalValue(LRA_COORDINATOR_PORT_KEY, Integer.class).orElse(COORDINATOR_SWARM_PORT);
        String lraCoordinatorPath = config.getOptionalValue(LRA_COORDINATOR_PATH_KEY, String.class)
                .orElse(NarayanaLRAClient.COORDINATOR_PATH_NAME);

        String lraCoordinatorUrl = String.format("http://%s:%d/%s", lcHost, lcPort, lraCoordinatorPath);

        String rcHost = config.getOptionalValue(LRA_RECOVERY_HOST_KEY, String.class).orElse(lcHost);
        int rcPort = config.getOptionalValue(LRA_RECOVERY_PORT_KEY, Integer.class).orElse(lcPort);
        String rcPath = config.getOptionalValue(LRA_RECOVERY_PATH_KEY, String.class).orElse(lraCoordinatorUrl);

        String rcUrl = String.format("http://%s:%d/%s", rcHost, rcPort, rcPath);

        LOGGER.infof(String.format("Using LRA coordinator %s", lraCoordinatorUrl));

        System.setProperty(LRA_COORDINATOR_HOST_KEY, lcHost);
        System.setProperty(LRA_COORDINATOR_PORT_KEY, Integer.toString(lcPort));
        System.setProperty(LRA_COORDINATOR_PATH_KEY, lraCoordinatorPath);
        System.setProperty(LRA_RECOVERY_HOST_KEY, rcHost);
        System.setProperty(LRA_RECOVERY_PORT_KEY, Integer.toString(rcPort));
        System.setProperty(LRA_RECOVERY_PATH_KEY, rcPath);

        try {
            NarayanaLRAClient.setDefaultCoordinatorEndpoint(new URI(lraCoordinatorUrl));
            NarayanaLRAClient.setDefaultRecoveryEndpoint(new URI(rcUrl));
        } catch (URISyntaxException e) {
            LOGGER.infof(String.format("Invalid LRA coordinator configuration: %s", e.getMessage()));
        }
    }

    /**
     * Called when the deployment is undeployed.
     */
    public void beforeShutdown(@Observes final BeforeShutdown bs) {
        LOGGER.info(LraAnnotationProcessingExtension.class.getName() + " extension deployment undeployed");
    }

    private String getMissingAnnotationError(AnnotatedMethod<?> method, ProcessAnnotatedType<?> classAnnotated,
        Class<?> lraTypeAnnotation, Class<?> complementaryAnnotation) {
        return String.format("Method '%s' of class '%s' annotated with '%s' should use complementary annotation %s",
            method.getJavaMember().getName(), classAnnotated.getAnnotatedType().getJavaClass().getName(),
            lraTypeAnnotation.getName(), complementaryAnnotation.getName());
    }

    private String getMissingAnnotationsForAsynchHandling(AnnotatedMethod<?> method, ProcessAnnotatedType<?> classAnnotated,
            Class<?> completionAnnotation) {
        return String.format("Method '%s' of class '%s' annotated with '%s' is defined to be asynchronous via @Suspend parameter annotation. " +
            "The LRA class has to contain @Status and @Forget annotations to activate such handling.",
                method.getJavaMember().getName(), classAnnotated.getAnnotatedType().getJavaClass().getName(),
                completionAnnotation.getName());
    }
}
