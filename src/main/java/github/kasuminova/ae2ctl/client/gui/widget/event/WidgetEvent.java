package github.kasuminova.ae2ctl.client.gui.widget.event;

import github.kasuminova.ae2ctl.client.gui.widget.base.DynamicWidget;
import github.kasuminova.ae2ctl.client.gui.widget.base.WidgetGui;

public abstract class WidgetEvent extends GuiEvent {
    protected final DynamicWidget sender;

    public WidgetEvent(final WidgetGui gui, final DynamicWidget sender) {
        super(gui);
        this.sender = sender;
    }

    public DynamicWidget getSender() {
        return sender;
    }
}