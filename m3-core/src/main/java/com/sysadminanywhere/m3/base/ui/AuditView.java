package com.sysadminanywhere.m3.base.ui;

import com.sysadminanywhere.m3.base.ui.menu.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import static com.sysadminanywhere.m3.base.i18n.Translations.t;

@Route("audit")
@jakarta.annotation.security.RolesAllowed("ADMIN")
@MenuItem(order=30,title="Audit log",section=MenuSection.ADMINISTRATION)
public class AuditView extends VerticalLayout implements HasDynamicTitle {
    private record Entry(Instant time,String actor,String entity,Long id,String operation) {}
    public AuditView(JdbcTemplate jdbc) {
        var grid=new Grid<Entry>();
        grid.addColumn(Entry::time).setHeader(t("Time"));
        grid.addColumn(Entry::actor).setHeader(t("User"));
        grid.addColumn(Entry::entity).setHeader(t("Entity"));
        grid.addColumn(Entry::id).setHeader("ID");
        grid.addColumn(Entry::operation).setHeader(t("Operation"));
        grid.setItems(query -> jdbc.query("SELECT occurred_at,actor,entity_type,entity_id,operation FROM configuration_audit ORDER BY audit_id DESC LIMIT ? OFFSET ?",
            (rs,n) -> new Entry(rs.getTimestamp(1).toInstant(),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class),rs.getString(5)),query.getLimit(),query.getOffset()).stream());
        add(new Button(t("Refresh"),event -> grid.getDataProvider().refreshAll()),grid);
        setSizeFull(); grid.setSizeFull();
    }
    @Override public String getPageTitle() { return t("Audit log"); }
}
