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
package org.jkiss.dbeaver.ui.editors;

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.INavigatorListener;
import org.jkiss.dbeaver.runtime.DBWorkbench;

/**
 * DatabaseEditorListener
 */
public class DatabaseEditorListener implements INavigatorListener
{
    private final IDatabaseEditor databaseEditor;
    private DBPDataSourceContainer dataSourceContainer;

    DatabaseEditorListener(IDatabaseEditor databaseEditor) {
        this.databaseEditor = databaseEditor;
        // Acquire datasource
        IEditorInput editorInput = databaseEditor.getEditorInput();
        if (editorInput instanceof IDatabaseEditorInput) {
            IDatabaseEditorInput databaseEditorInput = (IDatabaseEditorInput)editorInput;
            if (databaseEditorInput.getDatabaseObject() instanceof DBPDataSourceContainer) {
                dataSourceContainer = (DBPDataSourceContainer) databaseEditorInput.getDatabaseObject();
            } else if (databaseEditorInput.getNavigatorNode() != null) {
                dataSourceContainer = databaseEditorInput.getNavigatorNode().getDataSourceContainer();
            }
            if (dataSourceContainer != null) {
                dataSourceContainer.acquire(databaseEditor);
            }
        }
        // Register node listener
        DBWorkbench.getPlatform().getNavigatorModel().addListener(this);
    }

    public void dispose()
    {
        // Release datasource
        if (dataSourceContainer != null) {
            dataSourceContainer.release(databaseEditor);
            dataSourceContainer = null;
        }

        // Remove node listener
        DBWorkbench.getPlatform().getNavigatorModel().removeListener(this);
    }

    @Nullable
    public DBNNode getTreeNode()
    {
        IEditorInput input = databaseEditor.getEditorInput();
        return input instanceof IDatabaseEditorInput ? ((IDatabaseEditorInput) input).getNavigatorNode() : null;
    }

    @Override
    public void nodeChanged(final DBNEvent event)
    {
        if (isValuableNode(event.getNode())) {
            boolean closeEditor = false;
            if (event.getAction() == DBNEvent.Action.REMOVE) {
                closeEditor = true;
            } else if (event.getAction() == DBNEvent.Action.UPDATE) {
                if (event.getNodeChange() == DBNEvent.NodeChange.REFRESH ||
                    event.getNodeChange() == DBNEvent.NodeChange.LOAD)
                {
                    if (getTreeNode() == event.getNode()) {
                        databaseEditor.refreshPart(
                            event,
                            event.getNodeChange() == DBNEvent.NodeChange.REFRESH &&
                            event.getSource() == DBNEvent.FORCE_REFRESH);
                    }
                } else if (event.getNodeChange() == DBNEvent.NodeChange.UNLOAD) {
                    closeEditor = true;
                }
            }
            if (closeEditor) {
                if (DBWorkbench.getPlatform().isShuttingDown()) {
                    // Do not update editors during shutdown, just remove listeners
                    dispose();
                    return;
                }
                IWorkbenchWindow workbenchWindow = databaseEditor.getSite().getWorkbenchWindow();
                if (workbenchWindow != null) {
                    IWorkbenchPage workbenchPage = workbenchWindow.getActivePage();
                    if (workbenchPage != null) {
                        workbenchPage.closeEditor(databaseEditor, false);
                    }
                }
            }
        }
    }

    protected boolean isValuableNode(DBNNode node)
    {
        DBNNode editorNode = getTreeNode();
        return node == editorNode || (editorNode != null && editorNode.isChildOf(node));
    }

}