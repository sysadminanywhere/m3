package com.sysadminanywhere.m3.base.ui.menu;

public enum MenuSection {
    MESSAGING("Messaging", 1),
    SETTINGS("Settings", 2),
    ADMINISTRATION("Administration", 3);

    private final String title;
    private final int order;

    MenuSection(String title, int order) {
        this.title = title;
        this.order = order;
    }

    public String getTitle() {
        return title;
    }

    public int getOrder() {
        return order;
    }
}
