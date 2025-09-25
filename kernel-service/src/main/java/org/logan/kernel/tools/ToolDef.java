package org.logan.kernel.tools;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolDef {
    String name();
    String description() default "";
}
