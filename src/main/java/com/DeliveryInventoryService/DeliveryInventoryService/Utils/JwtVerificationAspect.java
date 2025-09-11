package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.DeliveryInventoryService.DeliveryInventoryService.Service.JwtService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.annotation.PrivateApi;

import jakarta.servlet.http.HttpServletRequest;

@Aspect
@Component
public class JwtVerificationAspect {
    private final JwtService jwtService;

    public JwtVerificationAspect(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Around("within(@org.springframework.web.bind.annotation.RestController *) && execution(* *(..))")
    public Object verifyJwt(ProceedingJoinPoint joinPoint) throws Throwable {
        // Check if method or class is annotated with @PrivateApi
        System.out.println("saving the phone to request 1");
        var method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        if (!method.isAnnotationPresent(PrivateApi.class)) {
            return joinPoint.proceed(); // skip JWT verification
        }

        System.out.println("saving the phone to request 2");

        // Get request
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                .getRequest();
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7); // remove "Bearer "
        if (!jwtService.validateToken(token)) {
            throw new RuntimeException("Invalid JWT token");
        }
        String phone = jwtService.extractPhone(token);
        String role = jwtService.extractRole(token);
        UUID id = jwtService.extractId(token);
        System.out.println("saving the phone to request" + phone);
        request.setAttribute("phone", phone);
        request.setAttribute("role", role);
        request.setAttribute("id", id);

        return joinPoint.proceed();
    }
}
