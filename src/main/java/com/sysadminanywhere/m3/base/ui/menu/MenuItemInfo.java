package com.sysadminanywhere.m3.base.ui.menu;

public record MenuItemInfo(
        String title,
        String icon,
        int order,
        MenuSection section,
        String path,
        Class<?> viewClass
) {
}
