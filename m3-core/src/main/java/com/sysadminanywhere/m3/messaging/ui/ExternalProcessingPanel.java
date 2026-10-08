package com.sysadminanywhere.m3.messaging.ui;
import com.sysadminanywhere.m3.messaging.service.ExternalProcessingService;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import static com.sysadminanywhere.m3.base.i18n.Translations.t;
public class ExternalProcessingPanel extends VerticalLayout {
    private final ExternalProcessingService service;private final Grid<ExternalProcessingService.Attempt> grid=new Grid<>();private Long id;
    public ExternalProcessingPanel(ExternalProcessingService service){
        this.service=service;setPadding(false);setWidthFull();add(new Span(t("External processing")),new Button(t("Attempt history"),e->history()),grid);
        grid.setWidthFull();grid.setAllRowsVisible(true);
        grid.addColumn(ExternalProcessingService.Attempt::recipient).setHeader(t("Recipient")).setWidth("100px");
        grid.addColumn(a->a.required()?t("Required"):t("Optional")).setHeader(t("Requirement")).setWidth("130px");
        grid.addColumn(a->com.sysadminanywhere.m3.base.i18n.Translations.enumLabel(a.status())).setHeader(t("Status")).setWidth("170px");
        grid.addColumn(ExternalProcessingService.Attempt::attemptNo).setHeader(t("Attempt")).setWidth("95px").setFlexGrow(0);
        grid.addColumn(a->a.overdue()?t("Overdue"):a.deadlineAt()==null?"":java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withLocale(com.sysadminanywhere.m3.base.i18n.Translations.locale()).withZone(java.time.ZoneId.systemDefault()).format(a.deadlineAt())).setHeader(t("Deadline")).setWidth("150px");
        if(new com.sysadminanywhere.m3.base.security.ServiceAccess().operate())grid.addComponentColumn(a->{
            var button=new Button(t("Create new attempt"),e->{var confirm=new ConfirmDialog();confirm.setHeader(t("Replay external processing"));
                confirm.setText(t("A new attempt will notify this recipient. Previous business effects may already exist; verify the recipient before continuing."));
                confirm.setConfirmText(t("Create new attempt"));confirm.setCancelText(t("Cancel"));confirm.setCancelable(true);
                var request=new ExternalProcessingService.Replay(java.util.UUID.randomUUID(),a.attemptId(),a.version(),true);
                confirm.addConfirmListener(ignored->{
                    try{service.replay(id,a.recipient(),request);}catch(org.springframework.web.server.ResponseStatusException conflict){Notification.show(t(conflict.getReason()));return;}
                    catch(RuntimeException failure){Notification.show(t("Replay could not be confirmed. Refresh before trying again."));return;}
                    try{load(id);}catch(RuntimeException failure){Notification.show(t("New attempt created. Refresh to see the updated status."));}
                });confirm.open();});
            button.setTooltipText(t("Replay external processing"));return button;
        }).setHeader(t("Actions")).setWidth("230px").setFlexGrow(0);
    }
    public void load(Long id){this.id=id;grid.setItems(service.attempts(id,false));}
    private void history(){
        try{
            var history=service.attemptHistory(id,200);var rows=history.attempts();
            var dialog=new com.vaadin.flow.component.dialog.Dialog();dialog.setHeaderTitle(t("Attempt history"));dialog.setWidth("min(1100px, calc(100vw - 32px))");dialog.setHeight("min(700px, calc(100vh - 32px))");
            var table=new Grid<ExternalProcessingService.Attempt>();table.setSizeFull();
            table.addColumn(ExternalProcessingService.Attempt::recipient).setHeader(t("Recipient"));table.addColumn(ExternalProcessingService.Attempt::attemptNo).setHeader(t("Attempt"));
            table.addColumn(a->com.sysadminanywhere.m3.base.i18n.Translations.enumLabel(a.status())).setHeader(t("Status"));
            table.addColumn(a->time(a.createdAt())).setHeader(t("Created"));table.addColumn(a->time(a.completedAt())).setHeader(t("Completed at"));
            table.addColumn(ExternalProcessingService.Attempt::detail).setHeader(t("Details"));table.addColumn(ExternalProcessingService.Attempt::attemptId).setHeader(t("Processing attempt ID"));
            table.getColumns().forEach(c->c.setAutoWidth(true).setResizable(true));table.setItems(rows);
            var content=new VerticalLayout(new Span(t("Showing latest {0} of {1} attempts",rows.size(),history.total())),table);content.setSizeFull();content.setPadding(false);dialog.add(content);dialog.getFooter().add(new Button(t("Close"),e->dialog.close()));dialog.open();
        }catch(RuntimeException failure){Notification.show(t("Attempt history is unavailable. Refresh and try again."));}
    }
    private static String time(java.time.Instant value){return value==null?"":java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(com.sysadminanywhere.m3.base.i18n.Translations.locale()).withZone(java.time.ZoneId.systemDefault()).format(value);}
}
