package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.*;

/** A short route through the existing configuration screens. */
@jakarta.annotation.security.RolesAllowed("ADMIN")
@Route("first-flow")
@com.vaadin.flow.component.dependency.Uses(ComboBox.class)
class FirstFlowView extends VerticalLayout implements HasDynamicTitle {
    FirstFlowView(ChannelSettingsService channels, RuleWorkerPoolService pools, RuleService rules) {
        setMaxWidth("850px");
        var name=new TextField(t("Name")); name.setRequired(true); name.setMaxLength(Rule.NAME_MAX_LENGTH);
        var source=new ComboBox<ChannelSettings>(t("Source Channel"));
        source.setItems(channels.findEnabledInboundChannelsByType(ChannelType.DIRECTORY)); source.setItemLabelGenerator(ChannelSettings::getName);
        var destination=new ComboBox<ChannelSettings>(t("Destination Channel"));
        destination.setItems(channels.findEnabledOutboundChannelsByType(ChannelType.DIRECTORY)); destination.setItemLabelGenerator(ChannelSettings::getName);
        var pool=new ComboBox<RuleWorkerPool>(t("Worker pool")); pool.setItems(pools.findAll()); pool.setItemLabelGenerator(RuleWorkerPool::getName);
        name.setWidthFull(); source.setWidthFull(); destination.setWidthFull(); pool.setWidthFull();
        var create=new Button(t("Create Rule"), event -> {
            if (name.getValue().isBlank() || source.getValue()==null || destination.getValue()==null || pool.getValue()==null) {
                Notification.show(t("Please fill all required fields")); return;
            }
            event.getSource().setEnabled(false);
            try {
                var rule=rules.saveConfiguration(null,name.getValue(),null,RuleType.INBOUND,source.getValue().getId(),0,false,
                        pool.getValue().getId(),destination.getValue().getId(),OutboundPayloadMode.BODY,java.util.Map.of());
                getUI().ifPresent(ui -> ui.navigate("rules/"+rule.getId()));
            } catch (RuntimeException invalid) {
                event.getSource().setEnabled(true);
                Notification.show(t("Error saving rule: ")+com.sysadminanywhere.m3.base.ui.SaveErrors.message(invalid));
            }
        });
        add(new H2(t("Set up your first message flow")),
                new Paragraph(t("1. Create inbound and outbound directory channels. Paths must exist in the worker's filesystem.")),
                navigate("Channels", "channels"),
                new Paragraph(t("2. Choose the source, destination and worker pool for an inbound rule.")),
                navigate("Rules", "rules"),
                new Paragraph(t("Create a disabled directory rule here, then review and enable it on the rule page.")),
                name,source,destination,pool,create,
                new Paragraph(t("3. Start a worker in that pool. A channel alone does not start receiving messages.")),
                navigate("Workers", "workers"),
                new Paragraph(t("4. Put a test file in the source directory, then inspect the inbound message and its outbound copy.")),
                navigate("Inbound Messages", "messages/inbound"),
                navigate("Outbound Messages", "messages/outbound"));
    }
    private Button navigate(String label, String route) {
        return new Button(t(label), event -> getUI().ifPresent(ui -> ui.navigate(route)));
    }
    @Override public String getPageTitle() { return t("Set up your first message flow"); }
}
