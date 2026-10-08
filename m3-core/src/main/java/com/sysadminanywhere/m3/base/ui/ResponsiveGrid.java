package com.sysadminanywhere.m3.base.ui;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.shared.Registration;

/** Keep action columns visible on desktop without covering data on narrow screens. */
public final class ResponsiveGrid {
    private static final int NARROW_SCREEN = 760;

    private ResponsiveGrid() { }

    public static void configure(Grid<?> grid) {
        var actionColumns = grid.getColumns().stream().filter(Grid.Column::isFrozenToEnd).toList();
        if (actionColumns.isEmpty()) return;
        grid.addAttachListener(event -> {
            var page = event.getUI().getPage();
            Registration resize = page.addBrowserWindowResizeListener(change ->
                    actionColumns.forEach(column -> column.setFrozenToEnd(change.getWidth() > NARROW_SCREEN)));
            page.executeJs("return window.innerWidth").then(Integer.class, width -> {
                if (grid.isAttached()) actionColumns.forEach(column -> column.setFrozenToEnd(width > NARROW_SCREEN));
            });
            Registration[] detach = new Registration[1];
            detach[0] = grid.addDetachListener(ignored -> {
                resize.remove();
                detach[0].remove();
            });
        });
    }
}
