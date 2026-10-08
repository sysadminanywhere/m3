package com.sysadminanywhere.m3.messaging.ui;
import com.sysadminanywhere.m3.messaging.service.OperationsService;
import com.sysadminanywhere.m3.base.ui.menu.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.*;
import static com.sysadminanywhere.m3.base.i18n.Translations.t;
@Route("operations") @jakarta.annotation.security.PermitAll
@MenuItem(order=8,title="Operations",section=MenuSection.MESSAGING)
public class OperationsView extends VerticalLayout implements HasDynamicTitle {
    private final OperationsService operations;private final Paragraph summary=new Paragraph();private final Span sampledAt=new Span();private final Span health=new Span();
    private final Grid<OperationsService.Alert> alerts=new Grid<>();private final Grid<OperationsService.Overdue> overdue=new Grid<>();
    public OperationsView(OperationsService operations){
        this.operations=operations;setWidthFull();health.getElement().setAttribute("role","status");health.getElement().setAttribute("aria-live","polite");health.getStyle().set("color","var(--lumo-error-text-color)");health.setVisible(false);
        add(new Button(t("Refresh"),e->refresh(true)),summary,sampledAt,health,new H3(t("Active alerts")),alerts,new H3(t("Overdue processing")),overdue);
        alerts.addColumn(OperationsService.Alert::severity).setHeader(t("Severity"));alerts.addColumn(OperationsService.Alert::detail).setHeader(t("Details"));alerts.addColumn(a->time(a.firstSeen())).setHeader(t("Time"));alerts.setAllRowsVisible(true);alerts.setWidthFull();
        overdue.addComponentColumn(a->new Button("#"+a.messageId(),e->getUI().ifPresent(ui->ui.navigate("messages/inbound",QueryParameters.simple(java.util.Map.of("id",Long.toString(a.messageId()))))))).setHeader(t("Message"));
        overdue.addColumn(OperationsService.Overdue::recipient).setHeader(t("Recipient"));overdue.addColumn(a->com.sysadminanywhere.m3.base.i18n.Translations.enumLabel(com.sysadminanywhere.m3.messaging.domain.MessageStatus.valueOf(a.status()))).setHeader(t("Status"));overdue.addColumn(a->time(a.deadlineAt())).setHeader(t("Deadline"));overdue.setWidthFull();overdue.setAllRowsVisible(true);
        addAttachListener(e->{refresh(false);e.getUI().setPollInterval(30000);var poll=e.getUI().addPollListener(p->refresh(false));addDetachListener(d->{poll.remove();e.getUI().setPollInterval(-1);});});
    }
    private void refresh(boolean manual){
        try{var s=manual?operations.refresh():operations.snapshot();summary.setText(t("Hot payload bytes: {0}; database bytes: {1}; pending processing events: {2}",s.metrics().get("m3_storage_hot_bytes"),s.metrics().get("m3_database_bytes"),s.metrics().get("m3_processing_events_pending")));alerts.setItems(s.alerts());overdue.setItems(s.overdue());sampledAt.setText(t("Last updated: {0}",time(s.sampledAt())));health.setText(t("Monitoring data is outdated. Check database connectivity."));health.setVisible(operations.stale(s));}
        catch(RuntimeException failure){health.setText(t("Monitoring data is unavailable. Displayed values may be outdated."));health.setVisible(true);}
    }
    private static String time(java.time.Instant value){return value==null?"":java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withLocale(com.sysadminanywhere.m3.base.i18n.Translations.locale()).withZone(java.time.ZoneId.systemDefault()).format(value);}
    public String getPageTitle(){return t("Operations");}
}
