package burp;

import io.github.swagger2repeater.ui.SwaggerRepeaterTab;

import java.awt.Component;

public final class BurpExtender implements IBurpExtender, ITab {
    private IBurpExtenderCallbacks callbacks;
    private SwaggerRepeaterTab tab;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.callbacks.setExtensionName("Swagger to Repeater");
        this.tab = new SwaggerRepeaterTab(callbacks);
        this.callbacks.addSuiteTab(this);
        this.callbacks.printOutput("Swagger to Repeater loaded");
    }

    @Override
    public String getTabCaption() {
        return "Swagger Repeater";
    }

    @Override
    public Component getUiComponent() {
        return tab;
    }
}