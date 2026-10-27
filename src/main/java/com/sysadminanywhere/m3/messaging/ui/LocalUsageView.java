package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.messaging.service.LocalUsageService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

@jakarta.annotation.security.RolesAllowed("ADMIN")
@Route("local-usage")
class LocalUsageView extends VerticalLayout implements HasDynamicTitle {
    private final LocalUsageService service;
    private final VerticalLayout content = new VerticalLayout();
    LocalUsageView(LocalUsageService service) {
        this.service=service;
        setMaxWidth("850px"); content.setPadding(false);
        add(new H2(t("Local usage summary")),
                new Paragraph(t("Last 30 days, based on retained messages. Counts may decrease when retention removes records. Data stays on this installation.")),
                new Button(t("Refresh"), event -> refresh()),content);
        refresh();
    }
    private void refresh() {
        content.removeAll();
        try {
            var summary=service.summary();
            content.add(new Paragraph(t("Active message days (UTC): {0}",summary.activeDays())),
                    new Paragraph(t("Inbound / outbound messages: {0} / {1}",summary.inbound(),summary.outbound())),
                    new Paragraph(t("Outbound sent / failed: {0} / {1}",summary.sent(),summary.failed())),
                    new Paragraph(t("Enabled rules: {0}",summary.enabledRules())),
                    new H2(t("Enabled channel types")));
            summary.channels().forEach(item -> content.add(new Paragraph(item.type()+": "+item.count())));
            content.add(new H2(t("Actions in enabled rules")));
            summary.actions().forEach(item -> content.add(new Paragraph(item.type()+": "+item.count())));
        } catch (RuntimeException failure) { content.add(new Paragraph(t("Usage summary is unavailable."))); }
    }
    @Override public String getPageTitle() { return t("Local usage summary"); }
}
