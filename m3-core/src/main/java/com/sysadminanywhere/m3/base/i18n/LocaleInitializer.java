package com.sysadminanywhere.m3.base.i18n;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public final class LocaleInitializer implements VaadinServiceInitListener {
    @Override public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(init -> {
            var ui = init.getUI();
            Locale selected = Translations.supported(ui.getLocale());
            var request = VaadinService.getCurrentRequest();
            String cookies = request == null ? null : request.getHeader("Cookie");
            if (cookies != null) for (String cookie : cookies.split(";")) {
                String[] entry = cookie.trim().split("=", 2);
                if (entry.length == 2 && entry[0].equals("m3-language"))
                    selected = Translations.supported(Locale.forLanguageTag(entry[1]));
            }
            ui.setLocale(selected);
            ui.getSession().setLocale(selected);
            ui.getElement().setAttribute("lang", selected.getLanguage());
            ui.getPage().executeJs("document.documentElement.lang = $0", selected.getLanguage());
        });
    }
}
