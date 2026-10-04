package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import com.sysadminanywhere.m3.messaging.service.RuleWorkerPoolService;
import com.sysadminanywhere.m3.messaging.service.WorkerPoolCapacityService;
import com.sysadminanywhere.m3.messaging.service.WorkerPoolMetricsService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route("workers")
@PageTitle("Rule Workers")
@MenuItem(title = "Workers", icon = "icons/worker.svg", order = 1, section = MenuSection.ADMINISTRATION)
class WorkerPoolsView extends VerticalLayout {
    private final RuleWorkerPoolService service;
    private final WorkerPoolCapacityService capacityService;
    private final WorkerPoolMetricsService metricsService;
    private final Grid<RuleWorkerPool> grid = new Grid<>();

    WorkerPoolsView(RuleWorkerPoolService service, WorkerPoolCapacityService capacityService,
                    WorkerPoolMetricsService metricsService) {
        this.service = service;
        this.capacityService = capacityService;
        this.metricsService = metricsService;
        var create = new Button("Create Worker Pool", event -> openCreateDialog());
        create.addThemeVariants(ButtonVariant.PRIMARY);
        var toolbar = new HorizontalLayout(create);
        toolbar.addClassName("page-toolbar");
        toolbar.setWidthFull();
        var controllerStatus = new Span(capacityService.isConfigured()
                ? "Docker controller connected · capacity is reconciled automatically"
                : "Docker controller is not configured · desired capacity is saved but containers will not start");
        controllerStatus.getStyle().set("color", "var(--m3-muted)");

        grid.addComponentColumn(this::poolCell).setHeader("Pool").setWidth("88px").setFlexGrow(1);
        grid.addColumn(capacityService::scaleTarget).setHeader("Capacity").setWidth("76px").setFlexGrow(0);
        grid.addColumn(pool -> capacityService.status(pool.getName()))
                .setHeader("Running").setWidth("110px").setFlexGrow(0);
        grid.addComponentColumn(pool -> {
            var load = metricsService.summary(pool.getId());
            var values = new VerticalLayout(
                    loadLine("Now", load.currentCpu(), load.currentMemory()),
                    loadLine("Peak", load.maximumCpu(), load.maximumMemory()),
                    loadLine("Avg", load.averageCpu(), load.averageMemory()));
            values.setPadding(false);
            values.setSpacing(false);
            values.addClassName("worker-pool-load");
            if (load.sampledAt() == null) values.getElement().setAttribute("title", "Waiting for the first Docker metrics sample");
            else values.getElement().setAttribute("title", "Last sample: " + load.sampledAt());
            return values;
        }).setHeader("Load · CPU / RAM").setWidth("165px").setFlexGrow(0);
        grid.addComponentColumn(pool -> {
            var edit = new Button(VaadinIcon.COG.create(), event -> openCapacityDialog(pool));
            edit.setAriaLabel("Change capacity for " + pool.getName());
            edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            var metrics = new Button(VaadinIcon.CHART.create(), event -> openContainerMetrics(pool));
            metrics.setAriaLabel("Container metrics for " + pool.getName());
            metrics.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            var actions = new HorizontalLayout(edit, metrics);
            actions.setPadding(false);
            actions.setSpacing(false);
            if ("default".equals(pool.getName())) return actions;
            var delete = new Button(VaadinIcon.TRASH.create(), event -> deletePool(pool));
            delete.setAriaLabel("Delete " + pool.getName());
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            delete.setEnabled(service.canDelete(pool.getId()));
            actions.add(delete);
            return actions;
        }).setHeader("Actions").setWidth("105px").setFlexGrow(0);
        grid.setEmptyStateText("No worker pools configured");
        grid.setSizeFull();
        setSizeFull();
        add(toolbar, controllerStatus, grid);
        refresh();
        addAttachListener(event -> {
            var ui = event.getUI();
            ui.setPollInterval(15000);
            var pollRegistration = ui.addPollListener(poll -> refresh());
            addDetachListener(detach -> {
                pollRegistration.remove();
                ui.setPollInterval(-1);
            });
        });
    }

    private Span loadLine(String label, double cpu, double memory) {
        return new Span(label + "  CPU " + percent(cpu) + "  RAM " + percent(memory));
    }

    private VerticalLayout poolCell(RuleWorkerPool pool) {
        var name = new Span(pool.getName());
        name.getStyle().set("overflow", "hidden").set("text-overflow", "ellipsis");
        name.getElement().setAttribute("title", pool.getName());
        var ruleCount = new Span(service.ruleCount(pool.getId()) + " rules");
        ruleCount.getStyle().set("color", "var(--m3-muted)").set("font-size", "var(--lumo-font-size-xs)");
        var cell = new VerticalLayout(name, ruleCount);
        cell.setPadding(false);
        cell.setSpacing(false);
        return cell;
    }

    private String percent(double value) { return String.format(java.util.Locale.ROOT, "%.0f%%", value); }

    private void openContainerMetrics(RuleWorkerPool pool) {
        var dialog = new Dialog(); dialog.setHeaderTitle("Containers  ·  " + pool.getName());
        dialog.setWidth("900px"); dialog.setMaxWidth("calc(100vw - 32px)");
        var table = new Grid<WorkerPoolMetricsService.ContainerSummary>();
        table.addColumn(value -> value.name() + "  ·  " + value.id().substring(0,12)).setHeader("Container");
        table.addColumn(value -> value.load().sampledAt().isBefore(java.time.Instant.now().minusSeconds(90))
                ? "Stale / stopped" : "Recent sample").setHeader("State");
        table.addColumn(value -> percent(value.load().currentCpu()) + " / " + percent(value.load().currentMemory())).setHeader("Now CPU / RAM");
        table.addColumn(value -> percent(value.load().maximumCpu()) + " / " + percent(value.load().maximumMemory())).setHeader("Peak CPU / RAM");
        table.addColumn(value -> percent(value.load().averageCpu()) + " / " + percent(value.load().averageMemory())).setHeader("Avg CPU / RAM");
        table.addColumn(value -> value.load().sampledAt()).setHeader("Last sample");
        table.setEmptyStateText("Waiting for Docker samples");
        Runnable update = () -> table.setItems(metricsService.containers(pool.getId()));
        update.run(); dialog.add(table);
        dialog.getFooter().add(new Button("Refresh",event -> update.run()),new Button("Close",event -> dialog.close()));
        dialog.open();
    }

    private void openCreateDialog() {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Create worker pool");
        var name = new TextField("Pool name");
        name.setPlaceholder("transformers");
        name.setHelperText("Lowercase letters, numbers and hyphens");
        var desired = numberField("Desired containers", 1);
        var min = numberField("Minimum", 1);
        var max = numberField("Maximum", 4);
        var autoScale = new Checkbox("Auto-scale from queued jobs");
        var jobsPerWorker = queueLimitField(50);
        var fields = new FormLayout(name, desired, min, max, autoScale, jobsPerWorker);
        var save = new Button("Create", event -> {
            try {
                var pool = service.create(name.getValue().trim(), desired.getValue(), min.getValue(), max.getValue(),
                        autoScale.getValue(), jobsPerWorker.getValue());
                dialog.close();
                refresh();
                reconcileAfterSave(pool);
            } catch (RuntimeException e) { showError(e); }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        var cancel = new Button("Cancel", event -> dialog.close());
        dialog.add(fields, new HorizontalLayout(save, cancel));
        dialog.open();
    }

    private void openCapacityDialog(RuleWorkerPool pool) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Capacity · " + pool.getName());
        var desired = numberField("Desired containers", pool.getDesiredReplicas());
        var min = numberField("Minimum", pool.getMinReplicas());
        var max = numberField("Maximum", pool.getMaxReplicas());
        var autoScale = new Checkbox("Auto-scale from queued jobs", pool.isAutoScaleEnabled());
        var jobsPerWorker = queueLimitField(pool.getPendingJobsPerWorker());
        var save = new Button("Save", event -> {
            try {
                var updated = service.updateCapacity(pool.getId(), desired.getValue(), min.getValue(), max.getValue(),
                        autoScale.getValue(), jobsPerWorker.getValue());
                dialog.close();
                refresh();
                reconcileAfterSave(updated);
            } catch (RuntimeException e) { showError(e); }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        dialog.add(new FormLayout(desired, min, max, autoScale, jobsPerWorker),
                new HorizontalLayout(save, new Button("Cancel", e -> dialog.close())));
        dialog.open();
    }

    private IntegerField numberField(String label, int value) {
        var field = new IntegerField(label);
        field.setValue(value);
        field.setMin(1);
        field.setMax(100);
        field.setStepButtonsVisible(true);
        field.setRequired(true);
        return field;
    }

    private IntegerField queueLimitField(int value) {
        var field = numberField("Pending jobs per worker", value);
        field.setMax(10000);
        field.setHelperText("Autoscaling target based on pending + processing rule jobs");
        return field;
    }

    private void deletePool(RuleWorkerPool pool) {
        try {
            service.delete(pool.getId());
            capacityService.removePool(pool.getName());
            refresh();
        } catch (RuntimeException e) { showError(e); }
    }

    private void refresh() { grid.setItems(service.findAll()); }

    private void reconcileAfterSave(RuleWorkerPool pool) {
        try {
            capacityService.reconcile(pool.getId());
            refresh();
        } catch (RuntimeException e) {
            Notification.show("Capacity saved; container reconciliation will retry: " + e.getMessage(),
                    6000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void showError(RuntimeException error) {
        Notification.show(error.getMessage() == null ? "Could not save worker pool" : error.getMessage(),
                4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
