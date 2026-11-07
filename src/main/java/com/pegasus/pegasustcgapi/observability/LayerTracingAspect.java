package com.pegasus.pegasustcgapi.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LayerTracingAspect {

    private static final String PROJECT_PACKAGE = "com.pegasus.pegasustcgapi..*";

    private final ObservationRegistry observationRegistry;

    public LayerTracingAspect(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Around("execution(public * " + PROJECT_PACKAGE + ".*(..)) && "
            + "(@within(org.springframework.web.bind.annotation.RestController) || "
            + "@within(org.springframework.stereotype.Controller))")
    public Object traceController(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint, "controller", "Controller");
    }

    @Around("execution(public * " + PROJECT_PACKAGE + ".*(..)) && "
            + "@within(org.springframework.stereotype.Service)")
    public Object traceService(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint, "service", "Service");
    }

    private Object observe(ProceedingJoinPoint joinPoint, String layer, String displayLayer) throws Throwable {
        Class<?> targetClass = AopUtils.getTargetClass(joinPoint.getTarget());
        String className = targetClass.getName();
        String methodName = joinPoint.getSignature().getName();
        String spanName = displayLayer + ": " + targetClass.getSimpleName() + "." + methodName;

        Observation observation = Observation.createNotStarted("app.layer", observationRegistry)
                .contextualName(spanName)
                .lowCardinalityKeyValue("code.namespace", className)
                .lowCardinalityKeyValue("code.function", methodName)
                .lowCardinalityKeyValue("app.layer", layer);

        return observation.observeChecked(
                (Observation.CheckedCallable<Object, Throwable>) joinPoint::proceed);
    }
}
