/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.properties;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TreeEditor;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.views.properties.IPropertySource2;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBPNamedObject2;
import org.jkiss.dbeaver.model.DBPObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.impl.PropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertySource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.properties.*;
import org.jkiss.dbeaver.ui.DefaultViewerToolTipSupport;
import org.jkiss.dbeaver.ui.UIElementAlignment;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.ObjectViewerRenderer;
import org.jkiss.dbeaver.ui.controls.bool.BooleanMode;
import org.jkiss.dbeaver.ui.controls.bool.BooleanStyleDecorator;
import org.jkiss.dbeaver.ui.internal.UIMessages;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.BeanUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.List;
import java.util.*;

/**
 * Driver properties control
 */
public class PropertyTreeViewer extends TreeViewer {
    public static final String LINE_SEPARATOR = GeneralUtils.getDefaultLineSeparator();

    public enum ExpandMode {
        NONE,
        FIRST,
        ALL,
    }

    private static final String CATEGORY_GENERAL = UIMessages.ui_properties_tree_viewer_category_general;
    private static final int NAME_COLUMN_WIDTH = 100;
    private static final int VALUE_COLUMN_WIDTH = 300;

    private boolean expandSingleRoot = true;
    private boolean namesEditable = false;
    private boolean newPropertiesAllowed = false;
    private boolean isMouseEventOnMacos = false; // [#10279] [#10366] [#10361]
    private TreeEditor treeEditor;

    private Font boldFont;
    private int selectedColumn = -1;
    private CellEditor curCellEditor;
    private DBPPropertyDescriptor selectedProperty;

    private String[] customCategories;
    private IBaseLabelProvider extraLabelProvider;
    private ObjectViewerRenderer renderer;
    private ExpandMode expandMode = ExpandMode.ALL;

    private final List<IPropertyChangeListener> propertyListeners = new ArrayList<>();

    public PropertyTreeViewer(Composite parent, int style)
    {
        super(parent, style | SWT.SINGLE | SWT.FULL_SELECTION);

        super.setContentProvider(new PropsContentProvider());
        final Tree treeControl = super.getTree();
        if (parent.getLayout() instanceof GridLayout) {
            GridData gd = new GridData(GridData.FILL_BOTH);
            gd.grabExcessHorizontalSpace = true;
            gd.grabExcessVerticalSpace = true;
            gd.minimumHeight = 120;
            gd.minimumWidth = 120;
            treeControl.setLayoutData(gd);
        }
        treeControl.setHeaderVisible(true);
        //treeControl.setLinesVisible(true);
        treeControl.addListener(SWT.PaintItem, new PaintListener());
        this.boldFont = UIUtils.makeBoldFont(treeControl.getFont());

        treeControl.addDisposeListener(e -> {
            UIUtils.dispose(boldFont);
        });

        new DefaultViewerToolTipSupport(this);


        TreeViewerColumn column = new TreeViewerColumn(this, SWT.NONE);
        //column.getColumn().setWidth(200);
        column.getColumn().setMoveable(true);
        column.getColumn().setText(UIMessages.properties_name);
        column.setLabelProvider(new PropsLabelProvider(true));
        column.getColumn().addListener(SWT.Selection, new SortListener());

        column = new TreeViewerColumn(this, SWT.NONE);
        //column.getColumn().setWidth(120);
        column.getColumn().setMoveable(true);
        column.getColumn().setText(UIMessages.properties_value);
        column.setLabelProvider(new PropsLabelProvider(false));

        /*
                List<? extends DBPProperty> props = ((DBPPropertyGroup) parent).getProperties();
                Collections.sort(props, new Comparator<DBPProperty>() {
                    public int compare(DBPProperty o1, DBPProperty o2)
                    {
                        return o1.getName().compareTo(o2.getName());
                    }
                });
                return props.toArray();

        */
        registerEditor();
        registerContextMenu();

        renderer = new ObjectViewerRenderer(this) {
            @Override
            public Object getCellValue(Object element, int columnIndex)
            {
                final TreeNode node = (TreeNode) element;
                if (columnIndex == 0) {
                    return node.category != null ?
                        node.category :
                        node.property.getDisplayName();
                }

                return getPropertyValue(node);
            }

            @Override
            public boolean isHyperlink(Object cellValue)
            {
                return cellValue instanceof DBSObject;
            }

            @Override
            public void navigateHyperlink(Object cellValue)
            {
                if (cellValue instanceof DBSObject) {
                    DBWorkbench.getPlatformUI().openEntityEditor((DBSObject) cellValue);
                }
            }

            @NotNull
            @Override
            protected UIElementAlignment getBooleanAlignment(@Nullable Boolean value) {
                return UIElementAlignment.LEFT;
            }
        };
    }

    public boolean isNamesEditable() {
        return namesEditable;
    }

    public void setNamesEditable(boolean namesEditable) {
        this.namesEditable = namesEditable;
    }

    public boolean isNewPropertiesAllowed() {
        return newPropertiesAllowed;
    }

    public void setNewPropertiesAllowed(boolean newPropertiesAllowed) {
        this.newPropertiesAllowed = newPropertiesAllowed;
    }

    public void loadProperties(DBPPropertySource propertySource)
    {
        loadProperties(null, null, propertySource);
    }

    public void loadProperties(DBRProgressMonitor monitor, DBPPropertySource propertySource)
    {
        loadProperties(monitor, null, propertySource);
    }

    protected void loadProperties(@Nullable DBRProgressMonitor monitor, TreeNode parent, DBPPropertySource propertySource)
    {
        // Make tree model
        customCategories = getCustomCategories();

        Map<String, TreeNode> categories = loadTreeNodes(monitor, parent, propertySource);
        if (customCategories != null) {
            for (String customCategory : customCategories) {
                TreeNode node = categories.get(customCategory);
                if (node == null) {
                    node = new TreeNode(parent, propertySource, customCategory);
                    categories.put(customCategory, node);
                }
            }
        }
        Object root;
        if (categories.size() == 1 && expandSingleRoot) {
            final Collection<TreeNode> values = categories.values();
            root = values.iterator().next();
        } else {
            root = categories.values();
        }

        super.setInput(root);

        disposeOldEditor();

        repackColumns();
    }

    public void repackColumns() {
        UIUtils.asyncExec(() -> {
            Tree tree = getTree();
            if (tree.isDisposed()) {
                return;
            }
            tree.setRedraw(false);
            try {
                PropertyTreeViewer.this.expandAll();
                UIUtils.packColumns(tree, true, new float[]{0.1f, 0.9f});

                switch (expandMode) {
                    case ALL:
                        break;
                    case FIRST:
                        Object root = getInput();
                        if (root instanceof Collection) {
                            Collection<?> rootItems = (Collection<?>) root;
                            if (!rootItems.isEmpty()) {
                                Object first = rootItems.iterator().next();
                                PropertyTreeViewer.this.collapseAll();
                                PropertyTreeViewer.this.expandToLevel(first, ALL_LEVELS);
                            }
                        } else {
                            PropertyTreeViewer.this.expandAll();
                        }
                        break;
                }
            } finally {
                tree.setRedraw(true);
            }
        });
    }

    /**
     * Change size of columns if their width are smaller
     * First column will be smaller then others because usually it's just a name
     */
    public void changeColumnsWidth() {
        Tree tree = getTree();
        if (tree != null && !tree.isDisposed()) {
            UIUtils.asyncExec(() -> {
                tree.setRedraw(false);
                TreeColumn[] columns = tree.getColumns();
                if (!ArrayUtils.isEmpty(columns) && columns.length > 1) {
                    for (int i = 0; i < columns.length; i++) {
                        if (i == 0) {
                            if (columns[0].getWidth() < NAME_COLUMN_WIDTH) {
                                columns[0].setWidth(NAME_COLUMN_WIDTH);
                            }
                        } else if (columns[i].getWidth() < VALUE_COLUMN_WIDTH) {
                            columns[i].setWidth(VALUE_COLUMN_WIDTH);
                        }
                    }
                }
                tree.setRedraw(true);
            });
        }
    }

    private Map<String, TreeNode> loadTreeNodes(@Nullable DBRProgressMonitor monitor, TreeNode parent, DBPPropertySource propertySource)
    {
        Map<String, TreeNode> categories = new LinkedHashMap<>();
        TreeNode lastCategory = null;
        final DBPPropertyDescriptor[] props = filterProperties(propertySource.getEditableValue(), propertySource.getProperties());
        for (DBPPropertyDescriptor prop : props) {
            if (prop instanceof ObjectPropertyDescriptor) {
                Object propertyValue = propertySource.getPropertyValue(monitor, prop.getId());
                if (!((ObjectPropertyDescriptor) prop).isPropertyVisible(propertySource.getEditableValue(), propertyValue)) {
                    // Skip non-visible properties
                    continue;
                }
            }
            String categoryName = prop.getCategory();
            if (CommonUtils.isEmpty(categoryName)) {
                categoryName = CATEGORY_GENERAL;
            }
            TreeNode category = (parent != null ? parent : categories.get(categoryName));
            if (category == null) {
                lastCategory = category = new TreeNode(null, propertySource, categoryName);
                categories.put(categoryName, category);
            }
            TreeNode propNode = new TreeNode(category, propertySource, prop);
            // Load nested object's properties
            if (!(propertySource instanceof IPropertySourceEditable)) {
                Class<?> propType = ((DBPPropertyDescriptor) prop).getDataType();
                if (propType != null) {
                    if (DBPObject.class.isAssignableFrom(propType)) {
                        Object propertyValue = propertySource.getPropertyValue(monitor, prop.getId());
                        if (propertyValue != null) {
                            PropertyCollector nestedCollector = new PropertyCollector(propertyValue, true);
                            if (nestedCollector.collectProperties()) {
                                categories.putAll(loadTreeNodes(monitor, propNode, nestedCollector));
                            }
                        }
                    } else if (BeanUtils.isCollectionType(propType)) {
                        Object propertyValue = propertySource.getPropertyValue(monitor, prop.getId());
                        if (propertyValue != null) {
                            Collection<?> collection;
                            if (BeanUtils.isArrayType(propType)) {
                                collection = Arrays.asList((Object[]) propertyValue);
                            } else {
                                collection = (Collection<?>) propertyValue;
                            }
                            PropertySourceCollection psc = new PropertySourceCollection(collection);
                            for (DBPPropertyDescriptor pd : psc.getProperties()) {
                                new TreeNode(propNode, psc, pd);
                            }
                        }
                    } else if (Map.class.isAssignableFrom(propType)) {
                        Map<String,?> propertyValue = (Map<String, ?>) propertySource.getPropertyValue(monitor, prop.getId());
                        if (propertyValue != null) {
                            PropertySourceMap psc = new PropertySourceMap(propertyValue);
                            for (DBPPropertyDescriptor pd : psc.getProperties()) {
                                new TreeNode(propNode, psc, pd);
                            }
                        }
                    }
                }
            }
        }
        return categories;
    }

    protected DBPPropertyDescriptor[] filterProperties(Object object, DBPPropertyDescriptor[] properties) {
        return properties;
    }

    public void clearProperties()
    {
        super.setInput(null);
    }

    protected void addProperty(Object node, DBPPropertyDescriptor property, boolean update)
    {
        if (node instanceof TreeNode) {
            TreeNode treeNode = (TreeNode) node;
            while (treeNode.property != null) {
                treeNode = treeNode.parent;
            }
            final TreeNode newNode = new TreeNode(treeNode, treeNode.propertySource, property);
            if (update) {
                handlePropertyCreate(newNode);
            }
        }
    }

    protected void removeProperty(Object node)
    {
        applyEditorValue();
        disposeOldEditor();
        if (node instanceof TreeNode) {
            TreeNode treeNode = (TreeNode) node;
            if (treeNode.propertySource != null) {
                treeNode.propertySource.resetPropertyValueToDefault(treeNode.property.getId());
            }
            treeNode.parent.children.remove(treeNode);
            handlePropertyRemove(treeNode);
        }
    }

    @Override
    public void refresh()
    {
        //disposeOldEditor();
        super.refresh();
    }

    private void disposeOldEditor()
    {
        if (curCellEditor != null) {
            curCellEditor.deactivate();
            curCellEditor.dispose();
            curCellEditor = null;
            selectedProperty = null;
        }
        Control oldEditor = treeEditor.getEditor();
        if (oldEditor != null) oldEditor.dispose();
    }

    private void registerEditor()
    {
        // Make an editor
        final Tree treeControl = super.getTree();
        treeEditor = new TreeEditor(treeControl);
        treeEditor.horizontalAlignment = SWT.CENTER;
        treeEditor.verticalAlignment = SWT.CENTER;
        treeEditor.minimumWidth = 50;

        treeControl.addSelectionListener(new SelectionListener() {

            @Override
            public void widgetDefaultSelected(SelectionEvent e)
            {
                //showEditor((TreeItem) e.item, true);
            }

            @Override
            public void widgetSelected(final SelectionEvent e)
            {
                TreeItem item = (TreeItem) e.item;
                if (RuntimeUtils.isMacOS()) { // [#10279] [#10366] [#10361]
                    showEditor(item, isMouseEventOnMacos);
                    isMouseEventOnMacos = false;
                    return;
                }
                showEditor(item, (e.stateMask & SWT.BUTTON_MASK) != 0);
            }
        });
        treeControl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e)
            {
                TreeItem item = treeControl.getItem(new Point(e.x, e.y));
                if (RuntimeUtils.isMacOS()) { // [#10279] [#10366] [#10361]
                    isMouseEventOnMacos = true;
                }
                if (item != null) {
                    selectedColumn = UIUtils.getColumnAtPos(item, e.x, e.y);
                } else {
                    selectedColumn = -1;
                    if (newPropertiesAllowed) {
                        TreeItem[] allItems = treeControl.getItems();
                        if (allItems.length > 0) {
                            TreeItem lastItem = allItems[allItems.length - 1];
                            if (lastItem.getData() instanceof TreeNode) {
                                TreeNode lastNode = (TreeNode) lastItem.getData();
                                if (!CommonUtils.isEmpty(lastNode.children)) {
                                    lastNode = lastNode.children.get(lastNode.children.size() - 1);
                                }
                                if (lastNode.property != null && CommonUtils.isEmpty(lastNode.property.getDisplayName())) {
                                    return;
                                }
                                if (lastNode.parent != null) lastNode = lastNode.parent;
                                addProperty(lastNode, new PropertyDescriptor(lastNode.category, "prop" + lastNode.children.size(), "", "", false, String.class, "", null), true);
                                allItems = treeControl.getItems();
                                TreeItem newItem = allItems[allItems.length - 1];
                                treeControl.setSelection(newItem);
                                selectedColumn = UIUtils.getColumnAtPos(newItem, e.x, e.y);
                            }
                        }
                    }
                }
            }
        });
        treeControl.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                // Set focus on editor
                if (curCellEditor != null) {
                    curCellEditor.setFocus();
                } else {
                    final TreeItem[] selection = treeControl.getSelection();
                    if (selection.length == 0) {
                        return;
                    }
                    showEditor(selection[0], true);
                }
                e.doit = false;
                e.detail = SWT.TRAVERSE_NONE;
            }
        });
    }

    private void showEditor(final TreeItem item, boolean isDef) {
        // Clean up any previous editor control
        disposeOldEditor();
        if (item == null) {
            return;
        }

        // Identify the selected row
        if (item.getData() instanceof TreeNode) {
            final Tree treeControl = super.getTree();
            final TreeNode prop = (TreeNode) item.getData();
            if (prop.property == null || !prop.isEditable()) {
                return;
            }
            final int columnIndex;
            if (selectedColumn == 0 && (!namesEditable || !(prop.property instanceof DBPNamedObject))) {
                columnIndex = 1;
            } else {
                columnIndex = this.selectedColumn;
            }
            int editStyle = SWT.LEFT;
            if (isHidePropertyValue(prop.property)) {
                editStyle |= SWT.PASSWORD;
            }
            final CellEditor cellEditor = PropertyEditorUtils.createPropertyEditor(UIUtils.getActiveWorkbenchWindow(), treeControl, prop.propertySource, prop.property, editStyle);
            if (cellEditor == null) {
                return;
            }
            if (cellEditor instanceof BooleanStyleDecorator) {
                ((BooleanStyleDecorator) cellEditor).setBooleanAlignment(UIElementAlignment.LEFT);
            }
            final Object propertyValue = columnIndex == 0 ? prop.property.getDisplayName() : prop.propertySource.getPropertyValue(null, prop.property.getId());
            final ICellEditorListener cellEditorListener = new ICellEditorListener() {
                @Override
                public void applyEditorValue()
                {
                    try {
                        //editorValueChanged(true, true);
                        final Object value = cellEditor.getValue();
                        final Object oldValue = columnIndex == 0 ? prop.property.getDisplayName() : prop.propertySource.getPropertyValue(null, prop.property.getId());
                        if (value instanceof String && ((String) value).isEmpty() && oldValue == null) {
                            // The same empty string
                            return;
                        }
                        if (DBUtils.compareDataValues(oldValue, value) != 0) {
                            if (columnIndex == 0) {
                                String newName = CommonUtils.toString(value);
                                String oldPropId = prop.property.getId();
                                Object oldPropValue = prop.propertySource.getPropertyValue(null, prop.property.getId());
                                ((DBPNamedObject2) prop.property).setName(newName);
                                if (oldPropValue != null) {
                                    prop.propertySource.resetPropertyValueToDefault(oldPropId);
                                    prop.propertySource.setPropertyValue(null, prop.property.getId(), oldPropValue);
                                }
                            } else {
                                prop.propertySource.setPropertyValue(
                                    null,
                                    prop.property.getId(),
                                    value);
                            }
                            handlePropertyChange(prop);
                        }

                        disposeOldEditor();
                    } catch (Exception e) {
                        DBWorkbench.getPlatformUI().showError("Error setting property value", "Error setting property '" + prop.property.getDisplayName() + "' value", e);
                    }
                }

                @Override
                public void cancelEditor()
                {
                    disposeOldEditor();
                }

                @Override
                public void editorValueChanged(boolean oldValidState, boolean newValidState)
                {
                }
            };
            cellEditor.addListener(cellEditorListener);
            if (propertyValue != null) {
                cellEditor.setValue(UIUtils.normalizePropertyValue(propertyValue));
            }
            curCellEditor = cellEditor;
            selectedProperty = prop.property;

            if (isDef) {
                cellEditor.activate();
            }
            final Control editorControl = cellEditor.getControl();
            if (editorControl != null) {
                editorControl.addTraverseListener(e -> {
                    /*if (e.detail == SWT.TRAVERSE_RETURN) {
                        e.doit = false;
                        e.detail = SWT.TRAVERSE_NONE;
                        cellEditorListener.applyEditorValue();
                        disposeOldEditor();
                    } else */if (e.detail == SWT.TRAVERSE_ESCAPE) {
                        e.doit = false;
                        e.detail = SWT.TRAVERSE_NONE;
                        disposeOldEditor();
                        if (prop.isEditable()) {
                            new ActionResetProperty(prop, false).run();
                        }
                    }
                });
                treeEditor.verticalAlignment = cellEditor.getLayoutData().verticalAlignment;
                treeEditor.horizontalAlignment = cellEditor.getLayoutData().horizontalAlignment;
                treeEditor.minimumWidth = cellEditor.getLayoutData().minimumWidth;
                treeEditor.grabHorizontal = cellEditor.getLayoutData().grabHorizontal;

                treeEditor.setEditor(editorControl, item, columnIndex);
            }
            if (isDef) {
                // Selected by mouse
                cellEditor.setFocus();
            }
        }
    }

    private void registerContextMenu()
    {
        // Register context menu
        {
            MenuManager menuMgr = new MenuManager();
            menuMgr.addMenuListener(manager -> {
                final IStructuredSelection selection = PropertyTreeViewer.this.getStructuredSelection();

                if (selection.isEmpty()) {
                    return;
                }
                final Object object = selection.getFirstElement();
                if (object instanceof TreeNode) {
                    final TreeNode prop = (TreeNode) object;
                    if (prop.property != null) {
                        manager.add(new Action(UIMessages.ui_properties_tree_viewer_action_copy_name) {
                            @Override
                            public void run()
                            {
                                UIUtils.setClipboardContents(Display.getDefault(), TextTransfer.getInstance(), prop.property.getDisplayName());
                            }
                        });

                        final String stringValue = CommonUtils.toString(getPropertyValue(prop));
                        if (!CommonUtils.isEmpty(stringValue)) {
                            manager.add(new Action(UIMessages.ui_properties_tree_viewer_action_copy_value) {
                                @Override
                                public void run()
                                {
                                    UIUtils.setClipboardContents(
                                        Display.getDefault(),
                                        TextTransfer.getInstance(),
                                        stringValue);
                                }
                            });
                        }
                        if (isPropertyChanged(prop) && prop.isEditable()) {
                            if (prop.propertySource instanceof IPropertySource2 && !prop.propertySource.isPropertyResettable(prop.property.getId())) {
                                // it is not resettable
                            } else {
                                manager.add(new ActionResetProperty(prop, false));
                                if (!isCustomProperty(prop.property)) {
                                    manager.add(new ActionResetProperty(prop, true));
                                }
                            }
                        }
                    }
                    manager.add(new Separator());
                    contributeContextMenu(
                        manager,
                        object,
                        prop.category != null ?
                            prop.category :
                            (prop.property == null ? null : prop.property.getCategory()),
                        prop.property);
                }
            });

            menuMgr.setRemoveAllWhenShown(true);
            Menu menu = menuMgr.createContextMenu(getTree());

            getTree().setMenu(menu);
            getTree().addDisposeListener(e -> menuMgr.dispose());
        }
    }

    private boolean isCustomProperty(DBPPropertyDescriptor property)
    {
        if (customCategories != null) {
            for (String category : customCategories) {
                if (category.equals(property.getCategory())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected String[] getCustomCategories()
    {
        return null;
    }

    protected void contributeContextMenu(IMenuManager manager, Object node, String category, DBPPropertyDescriptor property)
    {

    }

    public DBPPropertyDescriptor getPropertyFromElement(Object element) {
        if (element instanceof TreeNode) {
            return ((TreeNode) element).property;
        }
        return null;
    }

    private Object getPropertyValue(TreeNode prop)
    {
        if (prop.category != null) {
            return prop.category;
        } else {
            final Object propertyValue = prop.propertySource.getPropertyValue(null, prop.property.getId());
            return GeneralUtils.makeDisplayString(propertyValue);
        }
    }

    private boolean isPropertyChanged(TreeNode prop)
    {
        return prop.propertySource.isPropertySet(prop.property.getId());
    }

    private void handlePropertyChange(TreeNode prop)
    {
        super.update(prop, null);

        List<IPropertyChangeListener> listenersCopy;
        synchronized (propertyListeners) {
            listenersCopy = new ArrayList<>(propertyListeners);
        }
        if (!listenersCopy.isEmpty()) {
            PropertyChangeEvent event = new PropertyChangeEvent(
                this,
                CommonUtils.toString(prop.property.getId()),
                null,
                getPropertyValue(prop));

            for (IPropertyChangeListener listener : listenersCopy) {
                listener.propertyChange(event);
            }
        }

        // Send modify event
        Event event = new Event();
        event.data = prop.property;
        getTree().notifyListeners(SWT.Modify, event);
    }

    private void handlePropertyCreate(TreeNode prop)
    {
        handlePropertyChange(prop);
        super.refresh(prop.parent);
        super.expandToLevel(prop.parent, 1);
        super.reveal(prop);
        super.setSelection(new StructuredSelection(prop));
    }

    private void handlePropertyRemove(TreeNode prop)
    {
        handlePropertyChange(prop);
        super.refresh(prop.parent);
    }

    public void addPropertyChangeListener(IPropertyChangeListener listener) {
        synchronized (propertyListeners) {
            propertyListeners.add(listener);
        }
    }

    public void removePropertyChangeListener(IPropertyChangeListener listener) {
        synchronized (propertyListeners) {
            propertyListeners.remove(listener);
        }
    }

    public void setExpandMode(ExpandMode expandMode) {
        this.expandMode = expandMode;
    }

    protected void setExpandSingleRoot(boolean expandSingleRoot) {
        this.expandSingleRoot = expandSingleRoot;
    }

    public void setExtraLabelProvider(IBaseLabelProvider extraLabelProvider)
    {
        this.extraLabelProvider = extraLabelProvider;
    }

    public DBPPropertyDescriptor getSelectedProperty() {
        ISelection selection = getSelection();
        if (selection instanceof IStructuredSelection) {
            Object element = ((IStructuredSelection) selection).getFirstElement();
            if (element instanceof TreeNode) {
                final TreeNode prop = (TreeNode) element;
                return prop.property;
            }
        }
        return null;
    }

    public String getSelectedCategory() {
        ISelection selection = getSelection();
        if (selection instanceof IStructuredSelection) {
            Object element = ((IStructuredSelection) selection).getFirstElement();
            if (element instanceof TreeNode) {
                final TreeNode prop = (TreeNode) element;
                return prop.parent != null ? prop.parent.category : prop.category;
            }
        }
        return null;
    }

    public Object getCategoryNode(String category) {
        Object input = getInput();
        if (input instanceof Collection) {
            for (Object element : (Collection<?>)input) {
                if (element instanceof TreeNode && category.equals(((TreeNode) element).category)) {
                    return element;
                }
            }
        }
        return null;
    }

    public void saveEditorValues() {
        if (RuntimeUtils.isMacOS() && curCellEditor != null && curCellEditor.isActivated()) {
            try {
                // This is a hack. On MacOS buttons don't get focus so when user closes dialog
                // by clicking on Ok button CellEditor doesn't get FocusLost event and thus doesn't save its value.
                // This is workaround. Calling protected method focusLost in okPressed saves the value.
                // See:
                // https://github.com/dbeaver/dbeaver/issues/3553
                // https://github.com/dbeaver/dbeaver/issues/10366
                // https://github.com/dbeaver/dbeaver/issues/10361
                Method focusLost = CellEditor.class.getDeclaredMethod("focusLost");
                focusLost.setAccessible(true);
                focusLost.invoke(curCellEditor);
            } catch (Throwable throwable) {
                // Ignore
            }
        }
    }

    private static class TreeNode {
        final TreeNode parent;
        final DBPPropertySource propertySource;
        final DBPPropertyDescriptor property;
        final String category;
        final List<TreeNode> children = new ArrayList<>();

        private TreeNode(TreeNode parent, DBPPropertySource propertySource, DBPPropertyDescriptor property, String category)
        {
            this.parent = parent;
            this.propertySource = propertySource;
            this.property = property;
            this.category = category;
            if (parent != null) {
                parent.children.add(this);
            }
        }

        private TreeNode(TreeNode parent, DBPPropertySource propertySource, DBPPropertyDescriptor property)
        {
            this(parent, propertySource, property, null);
        }

        private TreeNode(TreeNode parent, DBPPropertySource propertySource, String category)
        {
            this(parent, propertySource, null, category);
        }

        boolean isEditable() {
            return property != null && property.isEditable(propertySource.getEditableValue());
        }

        @Override
        public String toString() {
            return
                property == null ?
                    category :
                    property.getId() + " (" + property.getDisplayName() + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof TreeNode) {
                final TreeNode node = (TreeNode) obj;
                if (this == node) return true;
                return
                    propertySource.getEditableValue() == node.propertySource.getEditableValue() &&
                    (category != null ? CommonUtils.equalObjects(category, node.category) :
                        property != null && node.property != null &&
                            CommonUtils.equalObjects(property.getId(), node.property.getId()));
            }
            return super.equals(obj);
        }
    }

    public static class NodeFilter extends ViewerFilter {
        private final String searchString;
        public NodeFilter(String searchString) {
            this.searchString = searchString.toUpperCase(Locale.ENGLISH);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element) {
            if (element instanceof TreeNode) {
                DBPPropertyDescriptor property = ((TreeNode) element).property;
                if (property != null) {
                    return property.getDisplayName().toUpperCase(Locale.ENGLISH).contains(searchString);
                } else if (((TreeNode) element).category != null) {
                    return true;
                }
            }
            return false;
        }
    }

    static class PropsContentProvider implements IStructuredContentProvider, ITreeContentProvider {
        @Override
        public void inputChanged(Viewer v, Object oldInput, Object newInput)
        {
        }

        @Override
        public void dispose()
        {
        }

        @Override
        public Object[] getElements(Object parent)
        {
            return getChildren(parent);
        }

        @Override
        public Object getParent(Object child)
        {
            if (child instanceof TreeNode) {
                return ((TreeNode) child).parent;
            } else {
                return null;
            }
        }

        @Override
        public Object[] getChildren(Object parent)
        {
            if (parent instanceof Collection) {
                return ((Collection<?>) parent).toArray();
            } else if (parent instanceof TreeNode) {
                // Add all available property groups
                return ((TreeNode) parent).children.toArray();
            } else {
                return new Object[0];
            }
        }

        @Override
        public boolean hasChildren(Object parent)
        {
            return getChildren(parent).length > 0;
        }
    }

    private class PropsLabelProvider extends CellLabelProvider {
        private final boolean isName;

        PropsLabelProvider(boolean isName)
        {
            this.isName = isName;
        }

        public String getText(Object obj, int columnIndex)
        {
            if (!(obj instanceof TreeNode)) {
                return ""; //$NON-NLS-1$
            }
            TreeNode node = (TreeNode) obj;
            if (columnIndex == 0) {
                if (node.category != null) {
                    return node.category;
                } else {
                    return node.property.getDisplayName();
                }
            } else {
                if (node.property != null) {
                    Object propertyValue = getPropertyValue(node);

                    Class<?> propDataType = node.property.getDataType();
                    if (Boolean.class == propDataType || Boolean.TYPE == propDataType) {
                        if (propertyValue != null && !(propertyValue instanceof Boolean)) {
                            propertyValue = CommonUtils.toBoolean(propertyValue);
                        }
                        if (renderer.getBooleanStyles().getMode() == BooleanMode.TEXT) {
                            return renderer.getBooleanStyles().getStyle((Boolean) propertyValue).getText();
                        } else {
                            return "";
                        }
                    } else if (propertyValue == null || renderer.isHyperlink(propertyValue)) {
                        return ""; //$NON-NLS-1$
                    } else if (isHidePropertyValue(node.property)) {
                        // Mask value
                        return maskHiddenPropertyValue(propertyValue);
                    } else if (BeanUtils.isCollectionType(propertyValue.getClass())) {
                        StringBuilder str = new StringBuilder();
                        str.append("[");
                        if (propertyValue instanceof Collection) {
                            int i = 0;
                            for (Object item : (Collection<?>) propertyValue) {
                                if (i > 0) str.append(",");
                                str.append(GeneralUtils.makeDisplayString(item));
                                i++;
                            }
                        } else {
                            int size = Array.getLength(propertyValue);
                            for (int i = 0; i < size; i++) {
                                if (i > 0) str.append(",");
                                str.append(GeneralUtils.makeDisplayString(Array.get(propertyValue, i)));
                            }
                        }
                        str.append("]");
                        return str.toString();
                    }
                    return ObjectViewerRenderer.getCellString(propertyValue, isName);
                } else {
                    return ""; //$NON-NLS-1$
                }
            }
        }

        @Nullable
        public Color getForeground(Object obj, int columnIndex) {
            if (obj instanceof TreeNode && columnIndex > 0) {
                TreeNode node = (TreeNode) obj;
                if (node.property != null) {
                    Object propertyValue = getPropertyValue(node);
                    Class<?> propertyDataType = node.property.getDataType();
                    if ((Boolean.class == propertyDataType || Boolean.TYPE == propertyDataType)) {
                        if (propertyValue != null && !(propertyValue instanceof Boolean)) {
                            propertyValue = CommonUtils.toBoolean(propertyValue);
                        }
                        if (renderer.getBooleanStyles().getMode() == BooleanMode.TEXT) {
                            return UIUtils.getSharedColor(renderer.getBooleanStyles().getStyle((Boolean) propertyValue).getColor());
                        } else {
                            return null;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public String getToolTipText(Object obj)
        {
            if (!(obj instanceof TreeNode)) {
                return null; //$NON-NLS-1$
            }
            TreeNode node = (TreeNode) obj;
            String toolTip;
            if (node.category != null) {
                toolTip = node.category;
            } else {
                toolTip = isName ? node.property.getDescription() : getText(obj, 1);
            }
            if (CommonUtils.isEmpty(toolTip)) {
                return null;
            }
            if (toolTip.contains("\\n")) {
                toolTip = toolTip.replace("\\n", "\n");
                toolTip = wrap(toolTip);
            }
            return toolTip;
        }

        // is got from https://blog.pdark.de/2009/12/26/swt-tree-and-tooltips/
        private String wrap (String s)
        {
            StringBuilder buffer = new StringBuilder ();

            String delim = "";
            for (String line: s.trim ().split ("\n"))
            {
                buffer.append (delim);
                delim = "\n";
                buffer.append (wrap (line, 100, "\n", true));
            }

            return buffer.toString ();
        }

        public String wrap(String str, int wrapLength, String newLineStr, boolean wrapLongWords) {
            if (str == null) {
                return null;
            } else {
                if (newLineStr == null) {
                    newLineStr = LINE_SEPARATOR;
                }

                if (wrapLength < 1) {
                    wrapLength = 1;
                }

                int inputLineLength = str.length();
                int offset = 0;
                StringBuilder wrappedLine = new StringBuilder(inputLineLength + 32);

                while(inputLineLength - offset > wrapLength) {
                    if (str.charAt(offset) == ' ') {
                        ++offset;
                    } else {
                        int spaceToWrapAt = str.lastIndexOf(32, wrapLength + offset);
                        if (spaceToWrapAt >= offset) {
                            wrappedLine.append(str.substring(offset, spaceToWrapAt));
                            wrappedLine.append(newLineStr);
                            offset = spaceToWrapAt + 1;
                        } else if (wrapLongWords) {
                            wrappedLine.append(str.substring(offset, wrapLength + offset));
                            wrappedLine.append(newLineStr);
                            offset += wrapLength;
                        } else {
                            spaceToWrapAt = str.indexOf(32, wrapLength + offset);
                            if (spaceToWrapAt >= 0) {
                                wrappedLine.append(str.substring(offset, spaceToWrapAt));
                                wrappedLine.append(newLineStr);
                                offset = spaceToWrapAt + 1;
                            } else {
                                wrappedLine.append(str.substring(offset));
                                offset = inputLineLength;
                            }
                        }
                    }
                }

                wrappedLine.append(str.substring(offset));
                return wrappedLine.toString();
            }
        }

        @Override
        public Point getToolTipShift(Object object)
        {
            return new Point(5, 5);
        }

        @Override
        public void update(ViewerCell cell)
        {
            Object element = cell.getElement();
            cell.setText(getText(element, cell.getColumnIndex()));
            cell.setForeground(getForeground(element, cell.getColumnIndex()));
            if (!(element instanceof TreeNode)) {
                return;
            }
            TreeNode node = (TreeNode) element;
            boolean changed = false;
            if (node.property != null) {
                changed = node.isEditable() && isPropertyChanged(node);
            }
            if (extraLabelProvider instanceof IFontProvider) {
                cell.setFont(((IFontProvider) extraLabelProvider).getFont(node.property));

            } else if (changed) {
                cell.setFont(boldFont);
            } else {
                cell.setFont(null);
            }
        }

    }

    private String maskHiddenPropertyValue(Object propertyValue) {
        return CommonUtils.isEmpty(CommonUtils.toString(propertyValue)) ? "" : "**********";
    }

    protected boolean isHidePropertyValue(DBPPropertyDescriptor property) {
        return false;
    }

    private class SortListener implements Listener {
        int sortDirection = SWT.DOWN;
        TreeColumn prevColumn = null;

        @Override
        public void handleEvent(Event e)
        {
            disposeOldEditor();

            getTree().setRedraw(false);
            try {
                Collator collator = Collator.getInstance(Locale.getDefault());
                TreeColumn column = (TreeColumn) e.widget;
                Tree tree = getTree();
                if (prevColumn == column) {
                    // Set reverse order
                    sortDirection = (sortDirection == SWT.UP ? SWT.DOWN : SWT.UP);
                }
                prevColumn = column;
                tree.setSortColumn(column);
                tree.setSortDirection(sortDirection);

                PropertyTreeViewer.this.setComparator(new ViewerComparator(collator) {
                    @Override
                    public int compare(Viewer viewer, Object e1, Object e2)
                    {
                        int mul = (sortDirection == SWT.UP ? 1 : -1);
                        int result;
                        TreeNode n1 = (TreeNode) e1, n2 = (TreeNode) e2;
                        if (n1.property != null && n2.property != null) {
                            result = n1.property.getDisplayName().compareTo(n2.property.getDisplayName());
                        } else if (n1.category != null && n2.category != null) {
                            result = n1.category.compareTo(n2.category);
                        } else {
                            result = 0;
                        }
                        return result * mul;
                    }
                });
            } finally {
                getTree().setRedraw(true);
            }
        }
    }

    private class ActionResetProperty extends Action {
        private final TreeNode prop;
        private final boolean toDefault;

        ActionResetProperty(TreeNode prop, boolean toDefault)
        {
            super(UIMessages.ui_properties_tree_viewer_action_reset_value + (!toDefault ? "" : UIMessages.ui_properties_tree_viewer__to_default)); //$NON-NLS-2$
            this.prop = prop;
            this.toDefault = toDefault;
        }

        @Override
        public void run()
        {
            if (prop.propertySource != null) {
                if (toDefault) {
                    prop.propertySource.resetPropertyValueToDefault(prop.property.getId());
                } else {
                    prop.propertySource.resetPropertyValue(null, prop.property.getId());
                }
            }
            handlePropertyChange(prop);
            PropertyTreeViewer.this.update(prop, null);
            disposeOldEditor();
        }
    }

    class PaintListener implements Listener {

        @Override
        public void handleEvent(Event event)
        {
            if (getTree().isDisposed()) {
                return;
            }
            switch (event.type) {
                case SWT.PaintItem: {
                    if (event.index == 1) {
                        if (treeEditor != null && treeEditor.getItem() == event.item && treeEditor.getEditor() != null &&
                            !treeEditor.getEditor().isDisposed() && treeEditor.getEditor().isVisible())
                        {
                            // Do not paint over active editor
                            return;
                        }
                        final TreeNode node = (TreeNode) event.item.getData();
                        if (node != null && node.property != null) {

                            Object cellValue = renderer.getCellValue(node, event.index);
                            renderer.paintCell(event, node, cellValue, event.item, node.property.getDataType(), event.index, node.isEditable(), (event.detail & SWT.SELECTED) == SWT.SELECTED);
                        }
                    }
                    break;
                }
            }
        }
    }
}
