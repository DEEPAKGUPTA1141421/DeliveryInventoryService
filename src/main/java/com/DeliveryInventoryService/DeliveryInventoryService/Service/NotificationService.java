package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Random;

/**
 * NotificationService
 * ────────────────────
 * Sends OTP via SMS and optional phone call.
 *
 * Provider: Twilio (configure twilio.* in application.properties).
 * Falls back to a mock/log mode when credentials are absent (dev).
 *
 * SMS → POST https://api.twilio.com/2010-04-01/Accounts/{SID}/Messages.json
 * Call → POST https://api.twilio.com/2010-04-01/Accounts/{SID}/Calls.json
 * with TwiML that reads the OTP aloud.
 */
@Service
@Slf4j
public class NotificationService {

    @Value("${twilio.account.sid:MOCK}")
    private String accountSid;

    @Value("${twilio.auth.token:MOCK}")
    private String authToken;

    @Value("${twilio.from.number:+10000000000}")
    private String fromNumber;

    @Value("${twilio.twiml.base.url:https://your-service.com}")
    private String twimlBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Random random = new Random();

    // ── OTP generation ────────────────────────────────────────────────────

    /**
     * Generates a secure 6-digit OTP string.
     */
    public String generateOtp() {
        int otp = 100000 + random.nextInt(900000);
        return String.valueOf(otp);
    }

    // ── SMS ───────────────────────────────────────────────────────────────

    /**
     * Sends an OTP SMS to the given phone number.
     *
     * @param phone   recipient in E.164 format, e.g. +919876543210
     * @param otp     6-digit code
     * @param context human context, e.g. "parcel pickup", "delivery"
     */
    public void sendOtpSms(String phone, String otp, String context) {
        String body = String.format(
                "[DeliveryCo] Your OTP for %s is %s. Valid for 10 minutes. Do not share.",
                context, otp);

        if (isMock()) {
            log.info("[MOCK SMS] To={} | Body={}", phone, body);
            return;
        }

        try {
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";
            var headers = twilioHeaders();
            var form = Map.of(
                    "To", phone,
                    "From", fromNumber,
                    "Body", body);

            restTemplate.postForObject(url, buildTwilioForm(form, headers), String.class);
            log.info("OTP SMS sent to {}", phone);
        } catch (Exception e) {
            log.error("Failed to send SMS to {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Places an automated voice call that reads the OTP aloud.
     * Uses a TwiML endpoint hosted on your server.
     *
     * @param phone recipient phone
     * @param otp   6-digit code to read
     */
    public void sendOtpCall(String phone, String otp) {
        if (isMock()) {
            log.info("[MOCK CALL] To={} | OTP={}", phone, otp);
            return;
        }

        try {
            String twiml = String.format(
                    "<Response><Say voice='alice'>Your delivery OTP is %s. I repeat, %s.</Say></Response>",
                    otp, otp);

            String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Calls.json";
            var form = Map.of(
                    "To", phone,
                    "From", fromNumber,
                    "Twiml", twiml);

            var headers = twilioHeaders();
            restTemplate.postForObject(url, buildTwilioForm(form, headers), String.class);
            log.info("OTP call placed to {}", phone);
        } catch (Exception e) {
            log.error("Failed to place call to {}: {}", phone, e.getMessage());
        }
    }

    // ── Generic notifications ─────────────────────────────────────────────

    /**
     * Sends a plain informational SMS (no OTP).
     */
    public void sendSms(String phone, String message) {
        if (isMock()) {
            log.info("[MOCK SMS] To={} | Msg={}", phone, message);
            return;
        }
        try {
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";
            var form = Map.of("To", phone, "From", fromNumber, "Body", message);
            restTemplate.postForObject(url, buildTwilioForm(form, twilioHeaders()), String.class);
        } catch (Exception e) {
            log.error("SMS send failed to {}: {}", phone, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean isMock() {
        return "MOCK".equals(accountSid);
    }

    private org.springframework.http.HttpHeaders twilioHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBasicAuth(accountSid, authToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    private org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> buildTwilioForm(
            Map<String, String> fields,
            org.springframework.http.HttpHeaders headers) {
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        fields.forEach(form::add);
        return new org.springframework.http.HttpEntity<>(form, headers);
    }
}