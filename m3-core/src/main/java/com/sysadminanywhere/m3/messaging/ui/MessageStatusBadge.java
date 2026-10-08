package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.i18n.Translations;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import com.vaadin.flow.component.html.Span;

/** A readable status label with a consistent visual treatment in both message lists. */
final class MessageStatusBadge extends Span {
    MessageStatusBadge(MessageStatus status) {
        super(Translations.enumLabel(status));
        getElement().setAttribute("title", getText());
        addClassName("status-badge");
        if (status != null) {
            addClassName(switch (status) {
                case LOADED, PROCESSING, PENDING -> "status-pending";
                case PROCESSING_FAILED, FAILED -> "status-failed";
                case SENT, PROCESSED -> "status-success";
            });
        }
    }
}
