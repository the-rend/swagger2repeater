package burp;

public interface IBurpExtenderCallbacks {
    void setExtensionName(String name);

    void addSuiteTab(ITab tab);

    void printOutput(String output);

    void printError(String error);

    void sendToRepeater(String host, int port, boolean useHttps, byte[] request, String tabCaption);
}