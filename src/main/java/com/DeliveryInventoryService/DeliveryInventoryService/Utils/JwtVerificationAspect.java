package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.DeliveryInventoryService.DeliveryInventoryService.Utils.annotation.PrivateApi;

import jakarta.servlet.http.HttpServletRequest;

@Aspect
@Component
public class JwtVerificationAspect {

    private final String expectedClientId;
    private final String expectedClientPassword;

    public JwtVerificationAspect(
            @Value("${client.id}") String expectedClientId,
            @Value("${client.password}") String expectedClientPassword) {
        this.expectedClientId = expectedClientId;
        this.expectedClientPassword = expectedClientPassword;
    }

    @Around("within(@org.springframework.web.bind.annotation.RestController *) && execution(* *(..))")
    public Object verifyJwt(ProceedingJoinPoint joinPoint) throws Throwable {
        var method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        if (!method.isAnnotationPresent(PrivateApi.class)) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                .getRequest();

        String clientId = request.getHeader("X-Client-Id");
        String clientPassword = request.getHeader("X-Client-Password");

        if (clientId == null || clientPassword == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing client credentials");
        }

        if (!expectedClientId.equals(clientId) || !expectedClientPassword.equals(clientPassword)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials");
        }

        return joinPoint.proceed();
    }
}
