package com.sysadminanywhere.m3.base.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;

/** Shared, font-independent wordmark for Community and Scale. */
public final class BrandMark extends Div {
    public BrandMark() {
        addClassName("brand-mark");
        getElement().setAttribute("role", "img");
        getElement().setAttribute("aria-label", "m3");
        Image light = new Image("icons/m3-logo.svg", "");
        light.addClassName("brand-mark-light");
        Image dark = new Image("icons/m3-logo-inverse.svg", "");
        dark.addClassName("brand-mark-dark");
        add(light, dark);
    }
}
