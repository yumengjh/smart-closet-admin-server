package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * Closet password rule, kept identical to the closet backend (smart-closet-server #155, #181):
 * at least 12 characters counted as code points, and at most 72 bytes once UTF-8 encoded (the
 * BCrypt limit; nothing is truncated). {@code null} is allowed and means "generate a temporary
 * password".
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ClosetPassword.Validator.class)
public @interface ClosetPassword {
    int MIN_CHARS = 12;
    int MAX_BYTES = 72;
    String TOO_SHORT = "密码至少需要 12 个字符。";
    String TOO_LONG = "密码不能超过 72 字节（英文、数字和符号各占 1 字节，汉字通常占 3 字节，表情符号占 4 字节）。";

    String message() default TOO_SHORT;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ClosetPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) return true;
            String message = null;
            if (value.codePointCount(0, value.length()) < MIN_CHARS) message = TOO_SHORT;
            else if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) message = TOO_LONG;
            if (message == null) return true;
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
            return false;
        }
    }
}
