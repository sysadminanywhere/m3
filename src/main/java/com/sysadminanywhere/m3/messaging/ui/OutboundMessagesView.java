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

@Route("messages/outbound")
@MenuItem(order = 2, icon = "icons/message.svg", title = "Outbound", section = MenuSection.MESSAGING, parent = "Messages")
class OutboundMessagesView extends MessageListView implements HasDynamicTitle {
    OutboundMessagesView(MessageService messages, MessageMetadataRepository metadata, OutboundSubmissionService submissions) {
        super(MessageDirection.OUTBOUND, "messages/outbound", messages, metadata, submissions);
    }
    @Override public String getPageTitle() { return t("Outbound Messages"); }
}