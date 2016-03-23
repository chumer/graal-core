package com.oracle.graal.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.impl.TVMCI;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

final class GraalTVMCI extends TVMCI {

    @Override
    public void onLoopCount(Node source, int count) {
        Node node = source;
        Node parentNode = source != null ? source.getParent() : null;
        while (node != null) {
            if (node instanceof OptimizedOSRLoopNode) {
                ((OptimizedOSRLoopNode) node).reportChildLoopCount(count);
            }
            parentNode = node;
            node = node.getParent();
        }
        if (parentNode != null && parentNode instanceof RootNode) {
            CallTarget target = ((RootNode) parentNode).getCallTarget();
            if (target instanceof OptimizedCallTarget) {
                ((OptimizedCallTarget) target).onLoopCount(count);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    Class<? extends TruffleLanguage> findLanguage(RootNode root) {
        return super.findLanguageClass(root);
    }

    void onFirstExecution(OptimizedCallTarget callTarget) {
        super.onFirstExecution(callTarget.getRootNode());
    }

}