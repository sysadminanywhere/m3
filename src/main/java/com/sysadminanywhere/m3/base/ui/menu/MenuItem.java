package com.sysadminanywhere.m3.base.ui.menu;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MenuItem {

    String title();

    String icon() default "";

    int order() default 0;

    MenuSection section() default MenuSection.MESSAGING;

}
