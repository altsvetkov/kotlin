/*
 * Copyright 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.dart.compiler.backend.js.ast;

/**
 * Taken from GWT project with modifications.
 * Original:
 *  repository: https://gwt.googlesource.com/gwt
 *  revision: e32bf0a95029165d9e6ab457c7ee7ca8b07b908c
 *  file: dev/core/src/com/google/gwt/dev/js/ast/JsModVisitor.java
 */

import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A visitor for iterating through and modifying an AST.
 */
public class JsVisitorWithContextImpl extends JsVisitorWithContext {

    private final Stack<JsContext<JsStatement>> statementContexts = new Stack<JsContext<JsStatement>>();

    public class ListContext<T extends JsNode> extends JsContext<T> {
        private List<T> collection;
        private int index;

        // Those are reset in every iteration of traverse()
        private final List<T> previous = new SmartList<T>();
        private final List<T> next = new SmartList<T>();
        private boolean removed = false;
        private boolean changed = false;

        @Override
        public <R extends T> void addPrevious(R node) {
            changed = true;
            previous.add(node);
        }

        @Override
        public <R extends T> void addNext(R node) {
            changed = true;
            next.add(node);
        }

        @Override
        public void removeMe() {
            changed = true;
            removed = true;
        }

        @Override
        public <R extends T> void replaceMe(R node) {
            changed = true;
            checkReplacement(collection.get(index), node);
            collection.set(index, node);
        }
        
        @Nullable
        @Override
        public T getCurrentNode() {
            if (index < collection.size()) {
                return collection.get(index);
            }

            return null;
        }

        protected void traverse(List<T> nodes) {
            assert previous.isEmpty(): "insertBefore() was called before traverse()";
            assert next.isEmpty(): "insertAfter() was called before traverse()";
            this.collection = nodes;
            List<T> changedNodes = new ArrayList<T>(collection.size());

            for (index = 0; index < nodes.size(); ++index) {
                removed = false;
                previous.clear();
                next.clear();
                T node = nodes.get(index);
                doTraverse(node, this);

                changedNodes.addAll(previous);
                if (!removed) {
                    changedNodes.add(nodes.get(index));
                }
                changedNodes.addAll(next);
            }

            if (changed) {
                nodes.clear();
                nodes.addAll(changedNodes);
            }
        }
    }

    private class LvalueContext extends NodeContext<JsExpression> {
    }

    private class NodeContext<T extends JsNode> extends JsContext<T> {
        protected T node;

        @Override
        public void removeMe() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R extends T> void replaceMe(R node) {
            checkReplacement(this.node, node);
            this.node = node;
        }

        @Nullable
        @Override
        public T getCurrentNode() {
            return node;
        }

        protected T traverse(T node) {
            this.node = node;
            doTraverse(node, this);
            return this.node;
        }
    }

    protected static void checkReplacement(@SuppressWarnings("UnusedParameters") JsNode origNode, JsNode newNode) {
        if (newNode == null) throw new RuntimeException("Cannot replace with null");
    }

    @Override
    protected <T extends JsNode> T doAccept(T node) {
        return new NodeContext<T>().traverse(node);
    }

    @Override
    protected JsExpression doAcceptLvalue(JsExpression expr) {
        return new LvalueContext().traverse(expr);
    }

    @Override
    protected <T extends JsStatement> JsStatement doAcceptStatement(T statement) {
        List<JsStatement> statements = new SmartList<JsStatement>(statement);
        doAcceptStatementList(statements);

        if (statements.size() == 1) {
            return statements.get(0);
        }

        return new JsBlock(statements);
    }

    @Override
    protected void doAcceptStatementList(List<JsStatement> statements) {
        ListContext<JsStatement> context = new ListContext<JsStatement>();
        statementContexts.push(context);
        context.traverse(statements);
        statementContexts.pop();
    }

    @Override
    protected <T extends JsNode> void doAcceptList(List<T> collection) {
        new ListContext<T>().traverse(collection);
    }

    @NotNull
    protected JsContext<JsStatement> getLastStatementLevelContext() {
        return statementContexts.peek();
    }

    @Override
    protected <T extends JsNode> void doTraverse(T node, JsContext ctx) {
        node.traverse(this, ctx);
    }

}
