/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.truffle;

import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.util.*;

import com.oracle.truffle.api.nodes.*;

public class OptimizedCallNodeProfile implements TruffleInliningProfile {

    private static final String REASON_RECURSION = "recursion";
    private static final String REASON_FREQUENCY_CUTOFF = "frequency < " + TruffleInliningMinFrequency.getValue();
    private static final String REASON_MAXIMUM_NODE_COUNT = "shallowTargetCount  > " + TruffleInliningMaxCalleeSize.getValue();
    private static final String REASON_MAXIMUM_TOTAL_NODE_COUNT = "inlinedTotalCount > " + TruffleInliningMaxCallerSize.getValue();

    private final OptimizedCallTarget callTarget;
    private final OptimizedCallNode callNode;

    private int targetDeepNodeCount;
    private List<OptimizedCallTarget> compilationRoots;
    private final int targetShallowNodeCount;
    private final double averageFrequency;
    private final double score;
    private String reason;

    public OptimizedCallNodeProfile(OptimizedCallTarget target, OptimizedCallNode callNode) {
        this.callNode = callNode;
        RootNode inlineRoot = callNode.getExecutedCallTarget().getRootNode();
        this.callTarget = target;
        this.targetShallowNodeCount = NodeUtil.countNodes(inlineRoot, null, false);
        this.targetDeepNodeCount = NodeUtil.countNodes(inlineRoot, null, true);
        this.compilationRoots = findCompilationRoots(callNode);
        this.averageFrequency = calculateFrequency(compilationRoots);
        this.score = calculateScore();
    }

    private double calculateFrequency(@SuppressWarnings("unused") List<OptimizedCallTarget> compilationRoots2) {
        return calculateSimpleFrequency();
    }

    public OptimizedCallNode getCallNode() {
        return callNode;
    }

    public double getScore() {
        return score;
    }

    public double calculateScore() {
        return averageFrequency / targetDeepNodeCount;
    }

    public boolean isInliningAllowed() {
        this.compilationRoots = findCompilationRoots(getCallNode());

        OptimizedCallTarget inlineTarget = callNode.getExecutedCallTarget();
        for (OptimizedCallTarget compilationRoot : compilationRoots) {
            if (compilationRoot == inlineTarget) {
                // recursive call found
                reason = REASON_RECURSION;
                return false;
            }
        }

        // frequency cut-off
        if (averageFrequency < TruffleInliningMinFrequency.getValue() && targetDeepNodeCount > TruffleInliningTrivialSize.getValue()) {
            reason = REASON_FREQUENCY_CUTOFF;
            return false;
        }

        if (targetShallowNodeCount > TruffleInliningMaxCalleeSize.getValue()) {
            reason = REASON_MAXIMUM_NODE_COUNT;
            return false;
        }

        this.targetDeepNodeCount = NodeUtil.countNodes(inlineTarget.getRootNode(), null, true);
        // The maximum total node count cannot be cached since it may change during inlining.
        int nextNodeCount = calculateInlinedTotalNodeCount(getCallNode());
        if (nextNodeCount > TruffleInliningMaxCallerSize.getValue()) {
            reason = REASON_MAXIMUM_TOTAL_NODE_COUNT;
            return false;
        }

        return true;
    }

    private int calculateInlinedTotalNodeCount(OptimizedCallNode node) {
        int currentNodeCount = 0;
        for (OptimizedCallTarget compilationRoot : compilationRoots) {
            if (compilationRoot.getRootNode().getParentInlinedCalls().isEmpty()) {
                TotalNodeCountVisitor visitor = new TotalNodeCountVisitor(node, targetDeepNodeCount);
                compilationRoot.getRootNode().accept(visitor);
                currentNodeCount = Math.max(currentNodeCount, visitor.count);
            }
        }
        return currentNodeCount;
    }

    private static class TotalNodeCountVisitor implements NodeVisitor {

        private final OptimizedCallNode inlinedNode;
        private final int inlinedNodeCount;

        private int count;

        public TotalNodeCountVisitor(OptimizedCallNode inlinedNode, int inlinedNodeCount) {
            this.inlinedNode = inlinedNode;
            this.inlinedNodeCount = inlinedNodeCount;
        }

        public boolean visit(Node node) {
            count++;
            if (node instanceof CallNode) {
                RootNode inlinedRoot = ((CallNode) node).getInlinedRoot();
                if (inlinedRoot != null) {
                    inlinedRoot.accept(this);
                } else if (node == inlinedNode) {
                    count += inlinedNodeCount;
                }
            }
            return true;
        }

    }

    double calculateSimpleFrequency() {
        return callNode.getCallCount() / (double) callTarget.getCompilationProfile().getCallCount();
    }

    double calculateAverageFrequency(List<OptimizedCallTarget> roots) {
        int compilationRootCallCountSum = 0;
        int compilationRootCount = 0;
        for (OptimizedCallTarget compilationRoot : roots) {
            if (compilationRoot.getRootNode().getParentInlinedCalls().isEmpty()) {
                compilationRootCallCountSum += compilationRoot.getCompilationProfile().getCallCount();
                compilationRootCount++;
            }
        }
        return (callNode.getCallCount() * compilationRootCount) / (double) compilationRootCallCountSum;
    }

    private static List<OptimizedCallTarget> findCompilationRoots(Node call) {
        RootNode root = call.getRootNode();
        if (root == null) {
            return Collections.emptyList();
        }
        List<OptimizedCallTarget> roots = new ArrayList<>();
        roots.add((OptimizedCallTarget) root.getCallTarget());
        for (CallNode callNode : root.getParentInlinedCalls()) {
            roots.addAll(findCompilationRoots(callNode));
        }
        return roots;
    }

    public int compareTo(TruffleInliningProfile o) {
        if (o instanceof OptimizedCallNodeProfile) {
            return Double.compare(((OptimizedCallNodeProfile) o).getScore(), getScore());
        }
        return 0;
    }

    public Map<String, Object> getDebugProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        OptimizedCallTarget.addASTSizeProperty(getCallNode().getExecutedCallTarget().getRootNode(), properties);
        properties.put("shallowCount", targetShallowNodeCount);
        properties.put("currentCount", calculateInlinedTotalNodeCount(null));
        properties.put("inlinedTotalCount", calculateInlinedTotalNodeCount(getCallNode()));
        properties.put("score", score);
        properties.put("frequency", averageFrequency);
        properties.put("callCount", callNode.getCallCount());
        properties.put("reason", reason);
        return properties;
    }

}