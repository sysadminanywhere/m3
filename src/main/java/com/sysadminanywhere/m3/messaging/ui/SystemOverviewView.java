package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.sysadminanywhere.m3.base.i18n.Translations;
import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.sysadminanywhere.m3.messaging.service.SystemOverviewService;
import com.sysadminanywhere.m3.messaging.service.SystemOverviewService.PoolStatus;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Map;

@jakarta.annotation.security.PermitAll
@Route("overview")
@MenuItem(order = 0, title = "Overview", section = MenuSection.MESSAGING)
class SystemOverviewView extends VerticalLayout implements HasDynamicTitle {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SystemOverviewView.class);
    private final com.sysadminanywhere.m3.messaging.service.SystemOverviewCache service;
    private final Div cards = new Div();
    private final Map<String, Span[]> metrics = new java.util.LinkedHashMap<>();
    private final Span updated = new Span();
    private final Span error = new Span();
    private final Grid<PoolStatus> pools = new Grid<>();
    private final DateTimeFormatter dates = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Translations.locale()).withZone(ZoneId.systemDefault());

    SystemOverviewView(com.sysadminanywhere.m3.messaging.service.SystemOverviewCache service) {
        this.service = service;
        addClassName("overview-page");
        var toolbar = new Div(new Button(t("Refresh"), event -> { service.invalidate(); refresh(); }), updated);
        toolbar.addClassName("overview-toolbar");
        cards.addClassName("overview-cards");
        for (String title : java.util.List.of("Pending jobs", "Processing jobs", "Failed inbound messages", "Failed outbound messages", "Pending receipts", "Retrying receipts")) {
            String target = title.equals("Failed inbound messages") ? "messages/inbound"
                    : title.equals("Failed outbound messages") ? "messages/outbound" : null;
            cards.add(card(title, 0, "", target));
        }
        error.addClassName("form-error"); error.getElement().setAttribute("role", "alert"); error.setVisible(false);
        var heading = new H2(t("Worker availability")); heading.addClassName("overview-heading");
        var explanation = new Paragraph(t("Workers report every 10 seconds. A heartbeat older than 35 seconds is considered stale. Start updated workers to enable monitoring."));
        explanation.addClassName("overview-description");
        pools.addColumn(PoolStatus::name).setHeader(t("Pool")).setFlexGrow(1).setWidth("150px");
        pools.addComponentColumn(pool -> {
            var label = new Span(t(pool.activeWorkers() > 0 ? "Available" : "No fresh heartbeat"));
            label.addClassNames("status-badge", pool.activeWorkers() > 0 ? "status-success" : "status-pending");
            return label;
        }).setHeader(t("Availability")).setWidth("190px").setFlexGrow(0);
        pools.addColumn(PoolStatus::activeWorkers).setHeader(t("Active workers")).setWidth("130px").setFlexGrow(0);
        pools.addColumn(PoolStatus::pending).setHeader(t("Pending jobs")).setWidth("130px").setFlexGrow(0);
        pools.addColumn(PoolStatus::processing).setHeader(t("Processing jobs")).setWidth("130px").setFlexGrow(0);
        pools.addColumn(PoolStatus::failed).setHeader(t("Failed jobs")).setWidth("130px").setFlexGrow(0);
        pools.addColumn(pool -> pool.lastHeartbeat() == null ? t("Never reported") : dates.format(pool.lastHeartbeat()))
                .setHeader(t("Last heartbeat")).setWidth("210px").setFlexGrow(0);
        pools.setEmptyStateText(t("No worker pools configured")); pools.setAllRowsVisible(true);
        pools.setWidthFull();
        add(toolbar, error, cards, heading, explanation, pools,
                new Button(t("Manage workers"), event -> getUI().ifPresent(ui -> ui.navigate("workers"))));
        addAttachListener(event -> {
            refresh();
            var ui = event.getUI(); ui.setPollInterval(15000);
            var poll = ui.addPollListener(ignored -> refresh());
            com.vaadin.flow.shared.Registration[] detach = new com.vaadin.flow.shared.Registration[1];
            detach[0] = addDetachListener(ignored -> { poll.remove(); ui.setPollInterval(-1); detach[0].remove(); });
        });
    }

    private void refresh() {
        try {
            var snapshot = service.snapshot();
            updateCard("Pending jobs", snapshot.pending(), snapshot.oldestPending() == null ? t("Queue is empty")
                            : t("Oldest pending: {0}", dates.format(snapshot.oldestPending())));
            updateCard("Processing jobs", snapshot.processing(), t("Currently claimed by workers"));
            updateCard("Failed inbound messages", snapshot.failedInbound(), t("Open failed messages"));
            updateCard("Failed outbound messages", snapshot.failedOutbound(), t("Open failed messages"));
            updateCard("Pending receipts", snapshot.pendingReceipts(), t("Receipts awaiting publication"));
            updateCard("Retrying receipts", snapshot.retryingReceipts(), t("Publication has already been attempted"));
            pools.setItems(snapshot.pools());
            updated.setText(t("Updated: {0}", dates.format(snapshot.sampledAt())));
            error.setVisible(false);
        } catch (RuntimeException unavailable) {
            log.warn("System overview refresh failed",unavailable);
            error.setText(t("Could not refresh monitoring. Previously displayed values may be stale.")); error.setVisible(true);
        }
    }

    private Div card(String title, long value, String description, String target) {
        var label = new Span(t(title)); label.addClassName("metric-label");
        var number = new Span(Long.toString(value)); number.addClassName("metric-value");
        var caption = new Span(description); caption.addClassName("metric-caption");
        metrics.put(title, new Span[] { number, caption });
        var card = new Div(label, number, caption); card.addClassName("metric-card");
        if (target != null) card.add(new Button(t("View"), event -> getUI().ifPresent(ui ->
                ui.navigate(target, QueryParameters.simple(Map.of("status", "FAILED"))))));
        return card;
    }
    private void updateCard(String title, long value, String description) {
        var metric = metrics.get(title);
        metric[0].setText(Long.toString(value)); metric[1].setText(description);
    }
    @Override public String getPageTitle() { return t("System Overview"); }
}
