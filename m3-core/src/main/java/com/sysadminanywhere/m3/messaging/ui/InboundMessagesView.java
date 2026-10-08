package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

@jakarta.annotation.security.PermitAll
@Route("messages/inbound")
@MenuItem(order = 1, icon = "icons/message.svg", title = "Inbound", section = MenuSection.MESSAGING, parent = "Messages")
class InboundMessagesView extends MessageListView implements HasDynamicTitle {
    InboundMessagesView(MessageService messages, MessageMetadataRepository metadata, OutboundSubmissionService submissions) {
        super(MessageDirection.INBOUND, "messages/inbound", messages, metadata, submissions);
    }
    @Override public String getPageTitle() { return t("Inbound Messages"); }
}