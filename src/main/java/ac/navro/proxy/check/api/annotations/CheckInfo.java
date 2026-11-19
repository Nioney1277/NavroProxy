package ac.navro.proxy.check.api.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CheckInfo {
    String name();

    String type() default "";

    int maxVl() default -1;

    double setback() default 25;

    double decay() default 0.05;
}
