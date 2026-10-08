package com.sysadminanywhere.m3.base.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.value.HasValueChangeMode;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.shared.Registration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks only persisted fields, excluding previews and already saved rule steps. */
public final class UnsavedChangesGuard {
    private final Component owner;
    private final List<Component> roots;
    private final Set<Component> observed = Collections.newSetFromMap(new IdentityHashMap<>());
    private Map<String,Object> baseline;
    private final Span indicator = new Span(t("Unsaved changes"));

    public UnsavedChangesGuard(Component owner, Component... roots) {
        this.owner = owner; this.roots = List.of(roots);
        indicator.addClassName("unsaved-indicator"); indicator.setVisible(false);
        indicator.getElement().setAttribute("role", "status");
        owner.addAttachListener(event -> {
            watch();
            var ui = event.getUI();
            var coordinator = ComponentUtil.getData(ui, Coordinator.class);
            if (coordinator == null) {
                coordinator = new Coordinator(ui); ComponentUtil.setData(ui, Coordinator.class, coordinator);
            }
            coordinator.guards.add(this);
            for (var root : this.roots) root.getElement().executeJs("window.m3Unsaved.register(this, $0)", owner.getElement());
            if (baseline == null) markSaved(); else refresh();
        });
        owner.addDetachListener(event -> {
            var coordinator = ComponentUtil.getData(event.getUI(), Coordinator.class);
            if (coordinator != null) {
                coordinator.guards.remove(this);
                if (coordinator.guards.isEmpty()) {
                    coordinator.registration.remove(); ComponentUtil.setData(event.getUI(), Coordinator.class, null);
                }
            }
        });
        if (owner instanceof Dialog dialog) dialog.addDialogCloseActionListener(event -> requestDiscard(dialog::close));
    }

    public Span indicator() { return indicator; }

    public void markSaved() { watch(); baseline = snapshot(); indicator.setVisible(false); sync(false); }
    public boolean isDirty() { return baseline != null && !baseline.equals(snapshot()); }

    public void requestDiscard(Runnable discard) {
        if (!isDirty()) { discard.run(); return; }
        confirm(() -> { markSaved(); discard.run(); }, () -> { });
    }

    private void watch() { roots.forEach(this::watch); }
    private void watch(Component component) {
        if (observed.add(component) && component instanceof HasValue<?,?> field && !field.isReadOnly()) {
            if (component instanceof HasValueChangeMode modes) modes.setValueChangeMode(ValueChangeMode.EAGER);
            field.addValueChangeListener(event -> { if (event.isFromClient()) { watch(); refresh(); } });
        }
        component.getChildren().forEach(this::watch);
    }

    private void refresh() { boolean dirty = isDirty(); indicator.setVisible(dirty); sync(dirty); }
    private void sync(boolean dirty) {
        if (owner.isAttached()) owner.getElement().executeJs("window.m3Unsaved.setDirty(this, $0)", dirty);
    }
    private Map<String,Object> snapshot() {
        var values = new LinkedHashMap<String,Object>();
        for (int i=0; i<roots.size(); i++) capture(roots.get(i), Integer.toString(i), values);
        return values;
    }
    private void capture(Component component, String path, Map<String,Object> values) {
        if (component instanceof HasValue<?,?> field && !field.isReadOnly()) values.put(path, field.getValue());
        var children = component.getChildren().toList();
        for (int i=0; i<children.size(); i++) capture(children.get(i), path + "/" + i, values);
    }

    private static void confirm(Runnable discard, Runnable keep) {
        var dialog = new ConfirmDialog();
        dialog.setHeader(t("Unsaved changes"));
        dialog.setText(t("Your changes have not been saved. Discard them and continue?"));
        dialog.setConfirmText(t("Discard changes")); dialog.setCancelText(t("Keep editing"));
        dialog.setCancelable(true);
        dialog.addConfirmListener(event -> discard.run());
        dialog.addCancelListener(event -> keep.run());
        dialog.open();
    }

    private static final class Coordinator {
        private final List<UnsavedChangesGuard> guards = new ArrayList<>();
        private final Registration registration;
        private Coordinator(UI ui) {
            registration = ui.addBeforeLeaveListener(event -> {
                if (guards.stream().noneMatch(UnsavedChangesGuard::isDirty)) return;
                var navigation = event.postpone();
                confirm(() -> {
                    List.copyOf(guards).forEach(UnsavedChangesGuard::markSaved);
                    navigation.proceed();
                }, navigation::cancel);
            });
        }
    }
}
