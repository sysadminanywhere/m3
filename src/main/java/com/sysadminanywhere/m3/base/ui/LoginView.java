package com.sysadminanywhere.m3.base.ui;

import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route(value="login", autoLayout=false)
@AnonymousAllowed
@PageTitle("m3 · Sign in")
public class LoginView extends VerticalLayout implements BeforeEnterObserver {
    private final LoginForm form = new LoginForm();
    public LoginView() {
        setSizeFull(); setAlignItems(Alignment.CENTER); setJustifyContentMode(JustifyContentMode.CENTER);
        addClassName("login-page");
        var i18n=com.vaadin.flow.component.login.LoginI18n.createDefault();
        i18n.getForm().setTitle(com.sysadminanywhere.m3.base.i18n.Translations.t("Log in"));
        i18n.getForm().setUsername(com.sysadminanywhere.m3.base.i18n.Translations.t("Username"));
        i18n.getForm().setPassword(com.sysadminanywhere.m3.base.i18n.Translations.t("Password"));
        i18n.getForm().setSubmit(com.sysadminanywhere.m3.base.i18n.Translations.t("Log in"));
        i18n.getErrorMessage().setTitle(com.sysadminanywhere.m3.base.i18n.Translations.t("Login failed"));
        i18n.getErrorMessage().setMessage(com.sysadminanywhere.m3.base.i18n.Translations.t("Check your username and password"));
        form.setI18n(i18n);
        form.setAction("login"); form.setForgotPasswordButtonVisible(false);
        BrandMark logo = new BrandMark();
        logo.addClassName("login-brand");
        add(logo, form);
    }
    @Override public void beforeEnter(BeforeEnterEvent event) {
        form.setError(event.getLocation().getQueryParameters().getParameters().containsKey("error"));
    }
}
