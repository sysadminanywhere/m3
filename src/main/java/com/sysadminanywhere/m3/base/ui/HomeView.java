package com.sysadminanywhere.m3.base.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

@Route(value = "")
public class HomeView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        event.forwardTo("overview");
    }
    @Override public String getPageTitle() { return t("Home"); }
}
