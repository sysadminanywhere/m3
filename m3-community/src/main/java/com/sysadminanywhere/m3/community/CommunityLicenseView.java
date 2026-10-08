package com.sysadminanywhere.m3.community;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import com.sysadminanywhere.m3.base.ui.menu.*;
import static com.sysadminanywhere.m3.base.i18n.Translations.t;

/** No activation endpoint or verifier is shipped in the standalone Community app. */
@Route("license") @RolesAllowed("ADMIN")
@MenuItem(title="License",icon="icons/worker.svg",order=4,section=MenuSection.ADMINISTRATION)
public class CommunityLicenseView extends VerticalLayout {
    public CommunityLicenseView() {
        add(new H2(t("Community")), new Paragraph(t("This distribution includes one worker slot and local login.")),
                new Paragraph(t("To activate Scale, install the official full Docker image using the same database. Data is retained; back up the database and secret key before switching distributions.")));
    }
}
