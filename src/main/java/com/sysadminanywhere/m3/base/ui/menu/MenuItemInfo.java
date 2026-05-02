package com.sysadminanywhere.m3.base.ui.menu;

public record MenuItemInfo(
        String title,
        String icon,
        int order,
        MenuSection section,
        String parent,
        String path,
        Class<?> viewClass
) {
}
