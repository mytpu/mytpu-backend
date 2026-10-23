package org.mytpu.tool.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Класс поля ответа и маска для внешних агентов (specs/personal-data-for-agents). У личного и чувствительного
 * инструмента пометка обязательна на каждом поле ответа, включая вложенные записи, — иначе шлюз не стартует.
 * Поле без пометки у публичного инструмента — публичное.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface Classified {
    Sensitivity value();

    /** Как показывать поле в режиме {@link DisclosureMode#MASKED}; для публичных полей не используется. */
    Mask mask() default Mask.HIDE;
}
