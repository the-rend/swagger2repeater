package io.github.swagger2repeater.ui;

import burp.IBurpExtenderCallbacks;
import io.github.swagger2repeater.model.ApiOperation;
import io.github.swagger2repeater.model.AuthConfig;
import io.github.swagger2repeater.model.AuthType;
import io.github.swagger2repeater.parser.OpenApiParser;
import io.github.swagger2repeater.request.BurpRepeaterDispatcher;
import io.github.swagger2repeater.request.RepeaterRequestBuilder;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class SwaggerRepeaterTab extends JPanel {
    private final IBurpExtenderCallbacks callbacks;
    private final OpenApiParser parser = new OpenApiParser();
    private final RepeaterRequestBuilder requestBuilder = new RepeaterRequestBuilder();
    private final BurpRepeaterDispatcher repeaterDispatcher;
    private final JTextPane detailsArea = new JTextPane();
    private javax.swing.JTable endpointsTable;
    private javax.swing.table.DefaultTableModel endpointsTableModel;
    private javax.swing.JCheckBox headerSelectAll;
    private int lastCheckboxRow = -1;
    private final JComboBox<AuthType> authTypeBox = new JComboBox<>(AuthType.values());
    private final JTextField headerNameField = new JTextField(16);
    private final JTextField headerValueField = new JTextField(20);
    private final JTextField usernameField = new JTextField(12);
    private final JPasswordField passwordField = new JPasswordField(12);
    private javax.swing.table.DefaultTableModel headersTableModel;
    private javax.swing.JTable headersTable;
    private List<ApiOperation> operations = new ArrayList<>();

    public SwaggerRepeaterTab(IBurpExtenderCallbacks callbacks) {
        super(new BorderLayout());
        this.callbacks = callbacks;
        this.repeaterDispatcher = new BurpRepeaterDispatcher(callbacks, requestBuilder);
        initializeUi();
    }

    private void initializeUi() {
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.CENTER));
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JButton loadFileButton = new JButton("Load Specification");
        JButton sendButton = new JButton("Send to Repeater");

        loadFileButton.addActionListener(event -> loadFromFile());
        sendButton.addActionListener(event -> sendSelectedOperation());

        topBar.add(loadFileButton);
        topBar.add(sendButton);

        // Auth panel removed per user request

        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailsArea.setEditable(false);
        detailsArea.setOpaque(true);

        // endpoints table with checkbox selection and separate endpoint columns
        endpointsTableModel = new javax.swing.table.DefaultTableModel(new Object[]{"Sel", "Group", "Method", "Name", "Path"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };
        endpointsTable = new javax.swing.JTable(endpointsTableModel);
        // remove inter-cell spacing to avoid narrow left gap
        endpointsTable.setIntercellSpacing(new Dimension(0, 0));
        endpointsTable.setRowMargin(0);
        endpointsTable.setShowGrid(false);
        endpointsTable.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_ALL_COLUMNS);
        endpointsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        endpointsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && endpointsTable.getSelectedRow() >= 0) {
                int modelRow = endpointsTable.convertRowIndexToModel(endpointsTable.getSelectedRow());
                ApiOperation op = operations.get(modelRow);
                updateDetails(op);
            }
        });
        // add select-all checkbox to header (clickable via header mouse listener)
        headerSelectAll = new javax.swing.JCheckBox();
        headerSelectAll.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        headerSelectAll.setSelected(true); // checked by default
        javax.swing.table.TableColumn selCol = endpointsTable.getColumnModel().getColumn(0);
        // fixed width for checkbox column
        selCol.setMinWidth(40);
        selCol.setMaxWidth(40);
        selCol.setPreferredWidth(40);

        // center checkbox cells
        class CenteredCheckBoxRenderer extends javax.swing.JCheckBox implements javax.swing.table.TableCellRenderer {
            CenteredCheckBoxRenderer() { setHorizontalAlignment(javax.swing.SwingConstants.CENTER); setOpaque(true); }
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                setSelected(Boolean.TRUE.equals(value));
                setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                return this;
            }
        }
        class CenteredCheckBoxEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
            private final javax.swing.JCheckBox checkBox = new javax.swing.JCheckBox();

            CenteredCheckBoxEditor() {
                checkBox.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                checkBox.setBorderPainted(false);
                checkBox.setOpaque(true);
                checkBox.addActionListener(e -> stopCellEditing());
            }

            @Override
            public Object getCellEditorValue() {
                return checkBox.isSelected();
            }

            @Override
            public java.awt.Component getTableCellEditorComponent(javax.swing.JTable table, Object value, boolean isSelected, int row, int column) {
                checkBox.setSelected(Boolean.TRUE.equals(value));
                checkBox.setBackground(table.getSelectionBackground());
                return checkBox;
            }
        }
        selCol.setCellRenderer(new CenteredCheckBoxRenderer());
        selCol.setCellEditor(new CenteredCheckBoxEditor());

        // draw thick group boundaries on checkbox cells too
        selCol.setCellRenderer(new CenteredCheckBoxRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                java.awt.Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                int top = isGroupStart(modelRow) ? 2 : 0;
                int bottom = isGroupEnd(modelRow) ? 2 : 0;
                Color separator = new Color(94, 156, 255);
                ((javax.swing.JComponent) component).setBorder(BorderFactory.createMatteBorder(top, 0, bottom, 0, separator));
                if (!isSelected) {
                    component.setBackground(table.getBackground());
                }
                return component;
            }
        });

        javax.swing.table.DefaultTableCellRenderer groupedTextRenderer = new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                java.awt.Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                int top = isGroupStart(modelRow) ? 2 : 0;
                int bottom = isGroupEnd(modelRow) ? 2 : 0;
                Color separator = new Color(94, 156, 255);
                ((javax.swing.JComponent) component).setBorder(BorderFactory.createMatteBorder(top, 0, bottom, 0, separator));
                if (!isSelected) {
                    component.setBackground(table.getBackground());
                }
                return component;
            }
        };
        for (int i = 1; i < endpointsTable.getColumnModel().getColumnCount(); i++) {
            endpointsTable.getColumnModel().getColumn(i).setCellRenderer(groupedTextRenderer);
        }

        // Shift+click on checkbox column toggles a contiguous range
        endpointsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                int viewRow = endpointsTable.rowAtPoint(e.getPoint());
                int viewCol = endpointsTable.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewCol != 0) {
                    return;
                }

                int modelRow = endpointsTable.convertRowIndexToModel(viewRow);

                if (e.isShiftDown() && lastCheckboxRow >= 0) {
                    boolean current = Boolean.TRUE.equals(endpointsTableModel.getValueAt(modelRow, 0));
                    boolean newValue = !current;
                    int start = Math.min(lastCheckboxRow, modelRow);
                    int end = Math.max(lastCheckboxRow, modelRow);
                    for (int row = start; row <= end; row++) {
                        endpointsTableModel.setValueAt(newValue, row, 0);
                    }
                    lastCheckboxRow = modelRow;
                    updateHeaderSelectState();
                    e.consume();
                } else {
                    lastCheckboxRow = modelRow;
                }
            }
        });

        // keep header select-all state in sync with row checkboxes
        endpointsTableModel.addTableModelListener(event -> {
            if (event.getColumn() == 0 || event.getColumn() == javax.swing.event.TableModelEvent.ALL_COLUMNS) {
                updateHeaderSelectState();
            }
        });

        selCol.setHeaderRenderer((table, value, isSelected, hasFocus, row, column) -> {
            javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.GridBagLayout());
            p.setOpaque(false);
            p.add(headerSelectAll);
            return p;
        });
        endpointsTable.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int col = endpointsTable.columnAtPoint(e.getPoint());
                if (col == 0) {
                    boolean allSelected = true;
                    for (int i = 0; i < endpointsTableModel.getRowCount(); i++) {
                        Boolean v = (Boolean) endpointsTableModel.getValueAt(i, 0);
                        if (v == null || !v) { allSelected = false; break; }
                    }
                    boolean newVal = !allSelected;
                    for (int i = 0; i < endpointsTableModel.getRowCount(); i++) {
                        endpointsTableModel.setValueAt(newVal, i, 0);
                    }
                    headerSelectAll.setSelected(newVal);
                    endpointsTable.getTableHeader().repaint();
                }
            }
        });

        JScrollPane listScroll = new JScrollPane(endpointsTable);
        JScrollPane detailsScroll = new JScrollPane(detailsArea);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setViewportBorder(BorderFactory.createEmptyBorder());

        listScroll.setPreferredSize(new Dimension(360, 400));
        detailsScroll.setPreferredSize(new Dimension(520, 400));

        // three-column split: endpoints | details | headers
        JPanel rightColumn = new JPanel();
        rightColumn.setLayout(new BoxLayout(rightColumn, BoxLayout.Y_AXIS));
        rightColumn.setBorder(BorderFactory.createTitledBorder("Headers"));

        // headers table
        headersTableModel = new javax.swing.table.DefaultTableModel(new Object[]{"Name", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        headersTable = new javax.swing.JTable(headersTableModel);
        JScrollPane headersScroll = new JScrollPane(headersTable);
        headersScroll.setPreferredSize(new Dimension(220, 200));

        // compact header controls: inputs in a tight row, buttons placed below inputs
        JPanel headerControls = new JPanel();
        headerControls.setLayout(new BoxLayout(headerControls, BoxLayout.Y_AXIS));
        headerControls.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JButton addHeaderBtn = new JButton("Add");
        JButton removeHeaderBtn = new JButton("Remove");
        addHeaderBtn.addActionListener(e -> {
            String name = headerNameField.getText().trim();
            String value = headerValueField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Header name required");
                return;
            }
            headersTableModel.addRow(new Object[]{name, value});
            headerNameField.setText("");
            headerValueField.setText("");
        });
        removeHeaderBtn.addActionListener(e -> {
            int sel = headersTable.getSelectedRow();
            if (sel >= 0) headersTableModel.removeRow(sel);
        });
        // inputs panel using GridBag so fields can get proportional widths
        JPanel inputs = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(0, 0, 0, 6);
        c.gridy = 0;

        JLabel nameLabel = new JLabel("Name: ");
        c.gridx = 0;
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        inputs.add(nameLabel, c);

        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        inputs.add(headerNameField, c);

        JLabel valueLabel = new JLabel("Value: ");
        c.gridx = 2;
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        inputs.add(valueLabel, c);

        c.gridx = 3;
        c.weightx = 1.0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        inputs.add(headerValueField, c);

        // buttons panel below inputs
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        addHeaderBtn.setPreferredSize(new Dimension(90, 24));
        removeHeaderBtn.setPreferredSize(new Dimension(100, 24));
        buttons.add(addHeaderBtn);
        buttons.add(removeHeaderBtn);

        headerControls.add(inputs);
        headerControls.add(javax.swing.Box.createVerticalStrut(6));
        headerControls.add(buttons);
        headerControls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        rightColumn.add(headerControls);
        rightColumn.add(headersScroll);

        JSplitPane middleSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailsScroll);
        middleSplit.setResizeWeight(0.4);
        JSplitPane bottomSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, middleSplit, rightColumn);
        bottomSplit.setResizeWeight(0.7);

        JPanel content = new JPanel(new BorderLayout());
        JLabel infoLabel = new JLabel("Load a Swagger/OpenAPI file, then send selected operations to Repeater.");
        infoLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        infoLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        infoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(infoLabel, BorderLayout.NORTH);
        content.add(bottomSplit, BorderLayout.CENTER);

        add(topBar, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);

        detailsArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        
    }

    private void loadFromFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path file = chooser.getSelectedFile().toPath();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            parseDocument(content);
        } catch (IOException e) {
            showError("Failed to read file: " + e.getMessage());
        }
    }
    private void parseDocument(String document) {
        try {
            operations = parser.parse(document).stream()
                .sorted(Comparator
                    .comparing(ApiOperation::groupName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ApiOperation::endpointName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
            // populate endpoints table
            endpointsTableModel.setRowCount(0);
            for (ApiOperation op : operations) {
                endpointsTableModel.addRow(new Object[]{
                        Boolean.TRUE,
                        op.groupName(),
                        op.method(),
                        op.endpointName(),
                        op.path()
                });
            }
            headerSelectAll.setSelected(true);
            updateEndpointsColumnMaxWidths();
            // display status in details pane: green count + hint
            try {
                StyledDocument doc = detailsArea.getStyledDocument();
                doc.remove(0, doc.getLength());
                SimpleAttributeSet green = new SimpleAttributeSet();
                StyleConstants.setForeground(green, new Color(0, 128, 0));
                StyleConstants.setBold(green, true);
                doc.insertString(doc.getLength(), "Loaded " + operations.size() + " endpoints\n", green);
                SimpleAttributeSet normal = new SimpleAttributeSet();
                StyleConstants.setForeground(normal, detailsArea.getForeground());
                doc.insertString(doc.getLength(), "Click an endpoint to show the details\n", normal);
            } catch (javax.swing.text.BadLocationException ex) {
                detailsArea.setText("Loaded " + operations.size() + " endpoints\nClick an endpoint to show the details");
            }
        } catch (IOException e) {
            showError("Failed to parse Swagger/OpenAPI document: " + e.getMessage());
        }
    }

    private void updateDetails(ApiOperation operation) {
        if (operation == null) {
            try {
                StyledDocument doc = detailsArea.getStyledDocument();
                doc.remove(0, doc.getLength());
            } catch (javax.swing.text.BadLocationException ex) {
                detailsArea.setText("");
            }
            return;
        }

        StringBuilder details = new StringBuilder();
        details.append(operation.method()).append(' ').append(operation.path()).append('\n');
        details.append("Endpoint: ").append(operation.endpointName()).append('\n');
        details.append("Group: ").append(operation.groupName()).append('\n');
        if (!operation.summary().isBlank()) {
            details.append("Summary: ").append(operation.summary()).append('\n');
        }
        if (!operation.description().isBlank()) {
            details.append("Description: ").append(operation.description()).append('\n');
        }
        if (!operation.baseUrl().isBlank()) {
            details.append("Base URL: ").append(operation.baseUrl()).append('\n');
        }
        details.append("\nHeaders:\n");
        operation.headers().forEach((name, value) -> details.append(name).append(": ").append(value).append('\n'));
        details.append("\nQuery parameters:\n");
        operation.queryParameters().forEach(parameter -> details.append(parameter.name()).append(" = ").append(parameter.exampleValue()).append('\n'));
        if (!operation.contentType().isBlank()) {
            details.append("\nContent-Type: ").append(operation.contentType()).append('\n');
        }
        if (!operation.exampleBody().isBlank()) {
            details.append("\nExample body:\n").append(operation.exampleBody()).append('\n');
        }

        try {
            StyledDocument doc = detailsArea.getStyledDocument();
            doc.remove(0, doc.getLength());
            SimpleAttributeSet normal = new SimpleAttributeSet();
            StyleConstants.setForeground(normal, detailsArea.getForeground());
            doc.insertString(0, details.toString(), normal);
            detailsArea.setCaretPosition(0);
        } catch (javax.swing.text.BadLocationException ex) {
            detailsArea.setText(details.toString());
        }
    }

    private void sendSelectedOperation() {
        // send checked endpoints
        java.util.List<ApiOperation> toSend = new java.util.ArrayList<>();
        for (int i = 0; i < endpointsTableModel.getRowCount(); i++) {
            Boolean sel = (Boolean) endpointsTableModel.getValueAt(i, 0);
            if (sel != null && sel) {
                toSend.add(operations.get(i));
            }
        }
        if (toSend.isEmpty()) {
            showError("Select one or more operations to send.");
            return;
        }

        var captions = computeCaptionsByGroup(operations);
        // collect extra headers from headers table
        java.util.Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();
        if (headersTableModel != null) {
            for (int r = 0; r < headersTableModel.getRowCount(); r++) {
                Object nameObj = headersTableModel.getValueAt(r, 0);
                Object valObj = headersTableModel.getValueAt(r, 1);
                if (nameObj != null) {
                    String name = String.valueOf(nameObj).trim();
                    String value = valObj == null ? "" : String.valueOf(valObj);
                    if (!name.isBlank()) {
                        extraHeaders.put(name, value);
                    }
                }
            }
        }
        int sent = 0;
        int failures = 0;
        for (ApiOperation op : toSend) {
            try {
                String forced = captions.getOrDefault(op, "");
                repeaterDispatcher.dispatch(op, currentAuthConfig(), forced, extraHeaders);
                sent++;
            } catch (RuntimeException ex) {
                failures++;
                callbacks.printError("Failed to send " + op.method() + " " + op.path() + ": " + ex.getMessage());
            }
        }
        callbacks.printOutput("Sent " + sent + " operations, " + failures + " failures.");
    }

    private void sendAllOperations() {
        if (operations.isEmpty()) {
            showError("Parse a Swagger/OpenAPI document first.");
            return;
        }
        // compute per-group captions by stripping the longest common prefix of path segments
        var captions = computeCaptionsByGroup(operations);

        int sentCount = 0;
        int failures = 0;

        for (ApiOperation operation : operations) {
            try {
                String caption = captions.getOrDefault(operation, "");
                repeaterDispatcher.dispatch(operation, currentAuthConfig(), caption);
                sentCount++;
            } catch (RuntimeException exception) {
                failures++;
                callbacks.printError("Failed to send " + operation.method() + " " + operation.path() + ": " + exception.getMessage());
            }
        }

        callbacks.printOutput("Bulk import complete: sent " + sentCount + " operations, " + failures + " failures.");
    }

    private java.util.Map<ApiOperation, String> computeCaptionsByGroup(java.util.List<ApiOperation> ops) {
        java.util.Map<String, java.util.List<ApiOperation>> byGroup = new java.util.LinkedHashMap<>();
        for (ApiOperation op : ops) {
            String g = op.groupName() == null ? "_default" : op.groupName();
            byGroup.computeIfAbsent(g, k -> new java.util.ArrayList<>()).add(op);
        }

        java.util.Map<ApiOperation, String> result = new java.util.HashMap<>();

        for (var entry : byGroup.entrySet()) {
            var list = entry.getValue();
            // build list of segment arrays
            java.util.List<String[]> segmentsList = new java.util.ArrayList<>();
            for (ApiOperation op : list) {
                String path = op.path() == null ? "" : op.path();
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                // trim leading/trailing slashes
                if (path.startsWith("/")) path = path.substring(1);
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                String[] segments = path.isBlank() ? new String[0] : path.split("/");
                segmentsList.add(segments);
            }

            // compute longest common prefix length; only strip when multiple operations share a prefix
            int prefixLen = 0;
            if (segmentsList.size() > 1) {
                int minLen = segmentsList.stream().mapToInt(a -> a.length).min().orElse(0);
                outer:
                for (int i = 0; i < minLen; i++) {
                    String seg = segmentsList.get(0)[i];
                    for (int j = 1; j < segmentsList.size(); j++) {
                        if (!seg.equals(segmentsList.get(j)[i])) {
                            break outer;
                        }
                    }
                    prefixLen++;
                }
            }

            // produce caption for each op
            for (int idx = 0; idx < list.size(); idx++) {
                ApiOperation op = list.get(idx);
                String[] segs = segmentsList.get(idx);
                String caption;
                if (segs.length <= prefixLen) {
                    caption = op.endpointName();
                } else {
                    String[] rem = java.util.Arrays.copyOfRange(segs, prefixLen, segs.length);
                    // keep braces for path parameters (e.g., {id})
                    caption = String.join("/", rem);
                }
                result.put(op, caption);
            }
        }

        return result;
    }

    private AuthConfig currentAuthConfig() {
        AuthType type = (AuthType) authTypeBox.getSelectedItem();
        if (type == null || type == AuthType.NONE) {
            return AuthConfig.none();
        }

        return switch (type) {
            case BEARER -> new AuthConfig(type, "Authorization", headerValueField.getText().trim(), "", "");
            case BASIC -> new AuthConfig(type, "Authorization", "", usernameField.getText().trim(), new String(passwordField.getPassword()));
            case HEADER -> new AuthConfig(type, headerNameField.getText().trim(), headerValueField.getText().trim(), "", "");
            case NONE -> AuthConfig.none();
        };
    }

    private void showError(String message) {
        callbacks.printError(message);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, "Swagger to Repeater", JOptionPane.ERROR_MESSAGE));
    }

    private void updateHeaderSelectState() {
        if (endpointsTableModel == null || headerSelectAll == null) {
            return;
        }
        boolean allSelected = endpointsTableModel.getRowCount() > 0;
        for (int i = 0; i < endpointsTableModel.getRowCount(); i++) {
            Boolean value = (Boolean) endpointsTableModel.getValueAt(i, 0);
            if (value == null || !value) {
                allSelected = false;
                break;
            }
        }
        headerSelectAll.setSelected(allSelected);
        if (endpointsTable != null) {
            endpointsTable.getTableHeader().repaint();
        }
    }

    private boolean isGroupStart(int modelRow) {
        if (modelRow <= 0 || modelRow >= operations.size()) {
            return modelRow == 0 && !operations.isEmpty();
        }
        String current = operations.get(modelRow).groupName();
        String prev = operations.get(modelRow - 1).groupName();
        return !java.util.Objects.equals(current, prev);
    }

    private boolean isGroupEnd(int modelRow) {
        if (modelRow < 0 || modelRow >= operations.size()) {
            return false;
        }
        if (modelRow == operations.size() - 1) {
            return true;
        }
        String current = operations.get(modelRow).groupName();
        String next = operations.get(modelRow + 1).groupName();
        return !java.util.Objects.equals(current, next);
    }

    private void updateEndpointsColumnMaxWidths() {
        if (endpointsTable == null || endpointsTableModel == null) {
            return;
        }

        javax.swing.table.TableColumnModel columnModel = endpointsTable.getColumnModel();
        for (int col = 0; col < columnModel.getColumnCount(); col++) {
            javax.swing.table.TableColumn column = columnModel.getColumn(col);
            int width = 0;

            javax.swing.table.TableCellRenderer headerRenderer = endpointsTable.getTableHeader().getDefaultRenderer();
            java.awt.Component headerComp = headerRenderer.getTableCellRendererComponent(
                    endpointsTable,
                    column.getHeaderValue(),
                    false,
                    false,
                    -1,
                    col
            );
            width = Math.max(width, headerComp.getPreferredSize().width);

            for (int row = 0; row < endpointsTableModel.getRowCount(); row++) {
                javax.swing.table.TableCellRenderer renderer = endpointsTable.getCellRenderer(row, col);
                java.awt.Component comp = endpointsTable.prepareRenderer(renderer, row, col);
                width = Math.max(width, comp.getPreferredSize().width);
            }

            width += 12;

            column.setMaxWidth(width);
            if (col == 0) {
                column.setMinWidth(width);
                column.setPreferredWidth(width);
            }
        }
    }

    private Color groupBackground(int modelRow) {
        if (modelRow < 0 || modelRow >= operations.size()) {
            return endpointsTable.getBackground();
        }
        int groupIndex = 0;
        for (int i = 1; i <= modelRow; i++) {
            if (!java.util.Objects.equals(operations.get(i - 1).groupName(), operations.get(i).groupName())) {
                groupIndex++;
            }
        }
        return (groupIndex % 2 == 0) ? new Color(36, 40, 46) : new Color(44, 49, 56);
    }
}