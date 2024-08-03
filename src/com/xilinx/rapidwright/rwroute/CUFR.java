/*
 *
 * Copyright (c) 2024 The Chinese University of Hong Kong.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, The Chinese University of Hong Kong.
 *
 * This file is part of RapidWright.
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
 *
 */

package com.xilinx.rapidwright.rwroute;

import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;

public class CUFR extends RWRoute {
    /* The number of connections that have already been routed before a certain iteration */
    private int connectionIdBase;
    /* A lock to keep the variables connectionRouted and connectionRoutedIteration synchronized */
    private ReentrantLock lock;
    /* A recursive partitioning ternary tree*/
    private PartitionTree partitionTree;

    public CUFR(Design design, RWRouteConfig config) {
        super(design, config);
    }
    

    // partitioning-tree-related classes ->
    /* The direction in which the cutline cuts the partition */
    public enum PartitionAxis {
        X,
        Y
    }

    /* The bounding box of a partition */
    public class PartitionBBox {
        public int xMin;
        public int xMax;
        public int yMin;
        public int yMax;
    
        public PartitionBBox(int xMin_, int xMax_, int yMin_, int yMax_) {
            xMin = xMin_;
            xMax = xMax_;
            yMin = yMin_;
            yMax = yMax_;
        }
    
        @Override
        public String toString() {
            return "bbox: [( " + xMin + ", " + yMin + " ), -> ( " + xMax + ", " + yMax + " )]";
        }
    }

    /* A partititoning tree */
    public class PartitionTreeNode {
        /* Connections contained in all subtrees */
        public List<Connection> connections;
        /* Left subtree */
        public PartitionTreeNode left;
        /* Middle subtree */
        public PartitionTreeNode middle;
        /* Right subtree */
        public PartitionTreeNode right;
        /* Bounding box of this node. */
        public PartitionBBox bbox;
    
        public PartitionTreeNode() {
            connections = null;
            left = null;
            right = null;
            bbox = null;
        }
        public void sortConnections() {
            connections.sort((connection1, connection2) -> {
                int comp = connection2.getNetWrapper().getConnections().size() - connection1.getNetWrapper().getConnections().size();
                if (comp == 0) {
                    return Short.compare(connection1.getHpwl(), connection2.getHpwl());
                } else {
                    return comp;
                }
            });
        }
    }

    public class PartitionTree {
        private PartitionTreeNode root;
        private PartitionBBox bbox;
        public PartitionTreeNode root() {return root;}
    
        public PartitionTree(List<Connection> connections, int xMax, int yMax) {
            bbox = new PartitionBBox(0, xMax, 0, yMax);
            root = new PartitionTreeNode();
            root.bbox = bbox;
            root.connections = connections;
            build(root);
        }
    
        private void build(PartitionTreeNode cur) {
            // sort the connections for routing
            cur.sortConnections();
    
            // find the best cutline
            int W = cur.bbox.xMax - cur.bbox.xMin + 1;
            int H = cur.bbox.yMax - cur.bbox.yMin + 1;
    
            int[] xTotalBefore = new int[W - 1];
            int[] xTotalAfter = new int[W - 1];
            int[] yTotalBefore = new int[H - 1];
            int[] yTotalAfter = new int[H - 1];
    
            for (Connection connection: cur.connections) {
                int xStart = Math.max(cur.bbox.xMin, clampX(connection.getXMinBB())) - cur.bbox.xMin;
                int xEnd   = Math.min(cur.bbox.xMax, clampX(connection.getXMaxBB())) - cur.bbox.xMin;
                assert(xStart >= 0);
                for (int x = xStart; x < W - 1; x++) {
                    xTotalBefore[x] ++;
                }
                for (int x = 0; x < xEnd; x++) {
                    xTotalAfter[x] ++;
                }
    
                int yStart = Math.max(cur.bbox.yMin, clampY(connection.getYMinBB())) - cur.bbox.yMin;
                int yEnd   = Math.min(cur.bbox.yMax, clampY(connection.getYMaxBB())) - cur.bbox.yMin;
                assert(yStart >= 0);
                for (int y = yStart; y < H - 1; y++) {
                    yTotalBefore[y] ++;
                }
                for (int y = 0; y < yEnd; y++) {
                    yTotalAfter[y] ++;
                }
            }
    
            double bestScore = Double.MAX_VALUE;
            double bestPos = Double.NaN;
            PartitionAxis bestAxis = PartitionAxis.X;
    
            int maxXBefore = xTotalBefore[W - 2];
            int maxXAfter = xTotalAfter[0];
            for (int x = 0; x < W - 1; x++) {
                int before = xTotalBefore[x];
                int after = xTotalAfter[x];
                if (before == maxXBefore || after == maxXAfter)
                    continue;
                double score = (double)Math.abs(xTotalBefore[x] - xTotalAfter[x]) / Math.max(xTotalBefore[x], xTotalAfter[x]);
                if (score < bestScore) {
                    bestScore = score;
                    bestPos = cur.bbox.xMin + x + 0.5;
                    bestAxis = PartitionAxis.X;
                }
            }
    
            int maxYBefore = yTotalBefore[H - 2];
            int maxYAfter = yTotalAfter[0];
            for (int y = 0; y < H - 1; y++) {
                int before = yTotalBefore[y];
                int after = yTotalAfter[y];
                if (before == maxYBefore || after == maxYAfter)
                    continue;
                double score = (double)Math.abs(yTotalBefore[y] - yTotalAfter[y]) / Math.max(yTotalBefore[y], yTotalAfter[y]);
                if (score < bestScore) {
                    bestScore = score;
                    bestPos = cur.bbox.yMin + y + 0.5;
                    bestAxis = PartitionAxis.Y;
                }
            }
    
            if (Double.isNaN(bestPos))
                return;
            
            // recursively build tree
            cur.left = new PartitionTreeNode();
            cur.left.connections = new ArrayList<>();
            cur.middle = new PartitionTreeNode();
            cur.middle.connections = new ArrayList<>();
            cur.right = new PartitionTreeNode();
            cur.right.connections = new ArrayList<>();
    
            if (bestAxis == PartitionAxis.X) {
                for (Connection connection: cur.connections) {
                    if (clampX(connection.getXMaxBB()) < bestPos) {
                        cur.left.connections.add(connection);
                    }
                    else if (clampX(connection.getXMinBB()) > bestPos) {
                        cur.right.connections.add(connection);
                    }
                    else {
                        cur.middle.connections.add(connection);
                    }
                }
                cur.left.bbox = new PartitionBBox(cur.bbox.xMin, (int)Math.floor(bestPos), cur.bbox.yMin, cur.bbox.yMax);
                cur.right.bbox = new PartitionBBox((int)Math.floor(bestPos) + 1, cur.bbox.xMax, cur.bbox.yMin, cur.bbox.yMax);
                cur.middle.bbox = cur.bbox;
            } else {
                assert(bestAxis == PartitionAxis.Y);
                for (Connection connection: cur.connections) {
                    if (clampY(connection.getYMaxBB()) < bestPos) {
                        cur.left.connections.add(connection);
                    }
                    else if (clampY(connection.getYMinBB()) > bestPos) {
                        cur.right.connections.add(connection);
                    }
                    else {
                        cur.middle.connections.add(connection);
                    }
                }
                cur.left.bbox = new PartitionBBox(cur.bbox.xMin, cur.bbox.xMax, cur.bbox.yMin, (int)Math.floor(bestPos));
                cur.right.bbox = new PartitionBBox(cur.bbox.xMin, cur.bbox.xMax, (int)Math.floor(bestPos) + 1, cur.bbox.yMax);
                cur.middle.bbox = cur.bbox;
            }
            assert(cur.left.connections.size() > 0 && cur.right.connections.size() > 0);
            build(cur.left);
            build(cur.right);
            if (cur.middle.connections.size() > 0)
                build(cur.middle);
            else
                cur.middle = null;
        }
    
        private int clampX(int x) { 
            return Math.min(Math.max(x, bbox.xMin), bbox.xMax); 
        }
    
        private int clampY(int y) { 
            return Math.min(Math.max(y, bbox.yMin), bbox.yMax); 
        }
    }
    // partitioning-tree-related classes <-

    // replace HashMap with ConcurrentHashMap ->
    public class RouteNodeGraphParallel extends RouteNodeGraph {
        protected Map<Tile, RouteNode[]> nodesMap;
        public RouteNodeGraphParallel(RuntimeTracker setChildrenTimer, Design design, RWRouteConfig config) {
            super(setChildrenTimer, design, config);
            nodesMap = new ConcurrentHashMap<>();
        }
       
    }

    public class RouteNodeGraphTimingDrivenParallel extends RouteNodeGraphParallel {
        /** The instantiated delayEstimator to compute delays */
        protected final DelayEstimatorBase delayEstimator;
        /** A flag to indicate if the routing resource exclusion should disable exclusion of nodes cross RCLK */
        protected final boolean maskNodesCrossRCLK;

        private Set<String> excludeAboveRclkString;
        private Set<String> excludeBelowRclkString;

        public RouteNodeGraphTimingDrivenParallel(RuntimeTracker rnodesTimer,
                                        Design design,
                                        RWRouteConfig config,
                                        DelayEstimatorBase delayEstimator) {
            super(rnodesTimer, design, config);
            this.delayEstimator = delayEstimator;
            this.maskNodesCrossRCLK = config.isMaskNodesCrossRCLK();

            excludeAboveRclkString = new HashSet<String>() {{
                add("SDQNODE_E_0_FT1");
                add("SDQNODE_E_2_FT1");
                add("SDQNODE_W_0_FT1");
                add("SDQNODE_W_2_FT1");
                add("EE12_BEG0");
                add("WW2_E_BEG0");
                add("WW2_W_BEG0");
            }};;

            excludeBelowRclkString = new HashSet<String>() {{
            add("SDQNODE_E_91_FT0");
            add("SDQNODE_E_93_FT0");
            add("SDQNODE_E_95_FT0");
            add("SDQNODE_W_91_FT0");
            add("SDQNODE_W_93_FT0");
            add("SDQNODE_W_95_FT0");
            add("EE12_BEG7");
            add("WW1_W_BEG7");
        }};;

            excludeAboveRclk = new HashSet<>();
            excludeBelowRclk = new HashSet<>();
            Device device = design.getDevice();
            Tile intTile = device.getArbitraryTileOfType(TileTypeEnum.INT);
            String[] wireNames = intTile.getWireNames();
            for (int wireIndex = 0; wireIndex < intTile.getWireCount(); wireIndex++) {
                String wireName = wireNames[wireIndex];
                if (excludeAboveRclkString.contains(wireName)) {
                    excludeAboveRclk.add(wireIndex);
                }
                if (excludeBelowRclkString.contains(wireName)) {
                    excludeBelowRclk.add(wireIndex);
                }
            }
        }

        private final Set<Integer> excludeAboveRclk;
        private final Set<Integer> excludeBelowRclk;

        protected class RouteNodeImpl extends RouteNodeGraphParallel.RouteNodeImpl {

            /** The delay of this rnode computed based on the timing model */
            private final float delay;

            protected RouteNodeImpl(Node node, RouteNodeType type) {
                super(node, type);
                delay = RouterHelper.computeNodeDelay(delayEstimator, node);
            }

            @Override
            public float getDelay() {
                return delay;
            }

            @Override
            public String toString() {
                StringBuilder s = new StringBuilder();
                s.append("node " + super.toString());
                s.append(", ");
                s.append("(" + getEndTileXCoordinate() + "," + getEndTileYCoordinate() + ")");
                s.append(", ");
                s.append(String.format("type = %s", getType()));
                s.append(", ");
                s.append(String.format("ic = %s", getIntentCode()));
                s.append(", ");
                s.append(String.format("dly = %f", delay));
                s.append(", ");
                s.append(String.format("user = %s", getOccupancy()));
                s.append(", ");
                s.append(getUsersConnectionCounts());
                return s.toString();
            }
        }

        @Override
        protected RouteNode create(Node node, RouteNodeType type) {
            return new RouteNodeImpl(node, type);
        }

        @Override
        protected boolean isExcluded(Node parent, Node child) {
            if (super.isExcluded(parent, child))
                return true;
            if (maskNodesCrossRCLK) {
                Tile tile = child.getTile();
                if (tile.getTileTypeEnum() == TileTypeEnum.INT) {
                    int y = tile.getTileYCoordinate();
                    if ((y-30)%60 == 0) { // above RCLK
                        return excludeAboveRclk.contains(child.getWireIndex());
                    } else if ((y-29)%60 == 0) { // below RCLK
                        return excludeBelowRclk.contains(child.getWireIndex());
                    }
                }
            }
            return false;
        }
    }
    // replace HashMap with ConcurrentHashMap <-

    // override/overload functions ->
    @Override
    protected RouteNodeGraphParallel createRouteNodeGraph() {
        if (config.isTimingDriven()) {
            /* An instantiated delay estimator that is used to calculate delay of routing resources */
            DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphTimingDrivenParallel(rnodesTimer, design, config, estimator);
        } else {
            return new RouteNodeGraphParallel(rnodesTimer, design, config);
        }
    }
    
    /**
     * <overload> Sets the costs of a rnode and pushes it to the queue.
     * @param childRnode A child rnode.
     * @param newPartialPathCost The upstream path cost from childRnode to the source.
     * @param newTotalPathCost Total path cost of childRnode.
     * @param queue The priority queue of routenodes to expand.
     * @param connectionId The special ID of the connection in a certain iteration.
     */
    protected void push(RouteNode childRnode, float newPartialPathCost, float newTotalPathCost, PriorityQueue<RouteNode> queue, int connectionId) {
        assert(childRnode.getPrev() != null || childRnode.getType() == RouteNodeType.PINFEED_O);
        childRnode.setLowerBoundTotalPathCost(newTotalPathCost);
        childRnode.setUpstreamPathCost(newPartialPathCost);
        // Use the number-of-connections-routed-so-far as the identifier for whether a rnode
        // has been visited by this connection before
        childRnode.setVisited(connectionId);
        queue.add(childRnode);
    }

    /**
     * Evaluates the cost of a child of a rnode and pushes the child into the queue after cost evaluation.
     * @param rnode The parent rnode of the child in question.
     * @param longParent A boolean value to indicate if the parent is a Long node
     * @param childRnode The child rnode in question.
     * @param connection The target connection being routed.
     * @param sharingWeight The sharing weight based on a connection's criticality and the shareExponent for computing a new sharing factor.
     * @param rnodeCostWeight The cost weight of the childRnode
     * @param rnodeLengthWeight The wirelength weight of childRnode's exact length.
     * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
     * @param rnodeDelayWeight The weight of childRnode's exact delay.
     * @param rnodeEstDlyWeight The weight of estimated delay from childRnode to the target.
     * @param queue The priority queue of routenodes to expand.
     */
    protected void evaluateCostAndPush(RouteNode rnode, boolean longParent, RouteNode childRnode, Connection connection, float sharingWeight, float rnodeCostWeight,
                                       float rnodeLengthWeight, float rnodeEstWlWeight,
                                       float rnodeDelayWeight, float rnodeEstDlyWeight, PriorityQueue<RouteNode> queue) {
        int countSourceUses = childRnode.countConnectionsOfUser(connection.getNetWrapper());
        float sharingFactor = 1 + sharingWeight* countSourceUses;

        // Set the prev pointer, as RouteNode.getEndTileYCoordinate() and
        // RouteNode.getSLRIndex() require this
        childRnode.setPrev(rnode);

        float newPartialPathCost = rnode.getUpstreamPathCost() + rnodeCostWeight * getNodeCost(childRnode, connection, countSourceUses, sharingFactor)
                                + rnodeLengthWeight * childRnode.getLength() / sharingFactor;

        if (config.isTimingDriven()) {
            newPartialPathCost += rnodeDelayWeight * (childRnode.getDelay() + DelayEstimatorBase.getExtraDelay(childRnode, longParent));
        }

        int childX = childRnode.getEndTileXCoordinate();
        int childY = childRnode.getEndTileYCoordinate();
        RouteNode sinkRnode = connection.getSinkRnode();
        int sinkX = sinkRnode.getBeginTileXCoordinate();
        int sinkY = sinkRnode.getBeginTileYCoordinate();
        int deltaX = Math.abs(childX - sinkX);
        int deltaY = Math.abs(childY - sinkY);
        if (connection.isCrossSLR()) {
            int deltaSLR = Math.abs(sinkRnode.getSLRIndex() - childRnode.getSLRIndex());
            if (deltaSLR != 0) {
                // Check for overshooting which occurs when child and sink node are in
                // adjacent SLRs and less than a SLL wire's length apart in the Y axis.
                if (deltaSLR == 1) {
                    int overshootByY = deltaY - RouteNodeGraphParallel.SUPER_LONG_LINE_LENGTH_IN_TILES;
                    if (overshootByY < 0) {
                        assert(deltaY < RouteNodeGraphParallel.SUPER_LONG_LINE_LENGTH_IN_TILES);
                        deltaY = RouteNodeGraphParallel.SUPER_LONG_LINE_LENGTH_IN_TILES - overshootByY;
                    }
                }

                // Account for any detours that must be taken to get to and back from the closest Laguna column
                int nextLagunaColumn = routingGraph.nextLagunaColumn[childX];
                int prevLagunaColumn = routingGraph.prevLagunaColumn[childX];
                int nextLagunaColumnDist = Math.abs(nextLagunaColumn - childX);
                int prevLagunaColumnDist = Math.abs(prevLagunaColumn - childX);
                if (sinkX >= childX) {
                    if (nextLagunaColumnDist <= prevLagunaColumnDist || prevLagunaColumn == Integer.MIN_VALUE) {
                        assert (nextLagunaColumn != Integer.MAX_VALUE);
                        deltaX = Math.abs(nextLagunaColumn - childX) + Math.abs(nextLagunaColumn - sinkX);
                    } else {
                        deltaX = Math.abs(childX - prevLagunaColumn) + Math.abs(sinkX - prevLagunaColumn);
                    }
                } else { // childX > sinkX
                    if (prevLagunaColumnDist <= nextLagunaColumnDist) {
                        assert (prevLagunaColumn != Integer.MIN_VALUE);
                        deltaX = Math.abs(childX - prevLagunaColumn) + Math.abs(sinkX - prevLagunaColumn);
                    } else {
                        deltaX = Math.abs(nextLagunaColumn - childX) + Math.abs(nextLagunaColumn - sinkX);
                    }
                }

                assert(deltaX >= 0);
            }
        }

        int distanceToSink = deltaX + deltaY;
        float newTotalPathCost = newPartialPathCost + rnodeEstWlWeight * distanceToSink / sharingFactor;

        if (config.isTimingDriven()) {
            newTotalPathCost += rnodeEstDlyWeight * (deltaX * 0.32 + deltaY * 0.16);
        }
		int connectionId = connectionIdBase + connection.hashCode();
        push(childRnode, newPartialPathCost, newTotalPathCost, queue, connectionId);
    }

    /**
     * Explores children (downhill rnodes) of a rnode for routing a connection and pushes the child into the queue,
     * if it is the target or is an accessible routing resource.
     * @param rnode The rnode popped out from the queue.
     * @param connection The connection that is being routed.
     * @param shareWeight The criticality-aware share weight for a new sharing factor.
     * @param rnodeCostWeight The cost weight of the childRnode
     * @param rnodeLengthWeight The wirelength weight of childRnode's exact wirelength.
     * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
     * @param rnodeDelayWeight The weight of childRnode's exact delay.
     * @param rnodeEstDlyWeight The weight of estimated delay to the target.
     * @param queue The priority queue of routenodes to expand.
     */
    private void exploreAndExpand(RouteNode rnode, Connection connection, float shareWeight, float rnodeCostWeight,
                                  float rnodeLengthWeight, float rnodeEstWlWeight,
                                  float rnodeDelayWeight, float rnodeEstDlyWeight, PriorityQueue<RouteNode> queue) {
        boolean longParent = config.isTimingDriven() && DelayEstimatorBase.isLong(rnode);
		int connectionId = connectionIdBase + connection.hashCode();
        for (RouteNode childRNode:rnode.getChildren()) {
            // Targets that are visited more than once must be overused
            assert(!childRNode.isTarget() || !childRNode.isVisited(connectionId) || childRNode.willOverUse(connection.getNetWrapper()));

            // If childRnode is preserved, then it must be preserved for the current net we're routing
            Net preservedNet;
            assert((preservedNet = routingGraph.getPreservedNet(childRNode)) == null ||
                    preservedNet == connection.getNetWrapper().getNet());

            if (childRNode.isVisited(connectionId)) {
                // Node must be in queue already.

                // Note: it is possible this is a cheaper path to childRNode; however, because the
                // PriorityQueue class does not support (efficiently) reducing the cost of nodes
                // already in the queue, this opportunity is discarded
                continue;
            }

            if (childRNode.isTarget()) {
                boolean earlyTermination = false;
                if (childRNode == connection.getSinkRnode() && connection.getAltSinkRnodes().isEmpty()) {
                    // This sink must be exclusively reserved for this connection already
                    assert(childRNode.getOccupancy() == 0 ||
                            childRNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                    earlyTermination = true;
                } else {
                    // Target is not an exclusive sink, only early terminate if this net will not
                    // (further) overuse this node
                    earlyTermination = !childRNode.willOverUse(connection.getNetWrapper());
                }

                if (earlyTermination) {
                    assert(!childRNode.isVisited(connectionId));
                    // nodesPushed += queue.size();
                    queue.clear();
                }
            } else {
                if (!isAccessible(childRNode, connection)) {
                    continue;
                }
                switch (childRNode.getType()) {
                    case WIRE:
                        if (!routingGraph.isAccessible(childRNode, connection)) {
                            continue;
                        }
                        if (!config.isUseUTurnNodes() && childRNode.getDelay() > 10000) {
                            // To filter out those nodes that are considered to be excluded with the masking resource approach,
                            // such as U-turn shape nodes near the boundary
                            continue;
                        }
                        break;
                    case PINBOUNCE:
                        // A PINBOUNCE can only be a target if this connection has an alternate sink
                        assert(!childRNode.isTarget() || connection.getAltSinkRnodes().isEmpty());
                        if (!isAccessiblePinbounce(childRNode, connection)) {
                            continue;
                        }
                        break;
                    case PINFEED_I:
                        if (!isAccessiblePinfeedI(childRNode, connection)) {
                            continue;
                        }
                        break;
                    case LAGUNA_I:
                        if (!connection.isCrossSLR() ||
                            connection.getSinkRnode().getSLRIndex() == childRNode.getSLRIndex()) {
                            // Do not consider approaching a SLL if not needing to cross
                            continue;
                        }
                        break;
                    case SUPER_LONG_LINE:
                        assert(connection.isCrossSLR() &&
                                connection.getSinkRnode().getSLRIndex() != rnode.getSLRIndex());
                        break;
                    default:
                        throw new RuntimeException("Unexpected rnode type: " + childRNode.getType());
                }
            }

            evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, rnodeCostWeight,
                    rnodeLengthWeight, rnodeEstWlWeight, rnodeDelayWeight, rnodeEstDlyWeight, queue);
            if (childRNode.isTarget() && queue.size() == 1) {
                // Target is uncongested and the only thing in the (previously cleared) queue, abandon immediately
                break;
            }
        }
    }

    /**
     * Prepares for routing a connection, including seeding the routing queue with
     * known-uncongested downstream-from-source routing segments acquired from prior
     * iterations, as well as marking known-uncongested upstream-from-sink segments
     * as targets.
     * @param connectionToRoute The target connection to be routed.
     * @param shareWeight The criticality-aware share weight for a new sharing factor.
     * @param rnodeCostWeight The cost weight of the childRnode
     * @param rnodeLengthWeight The wirelength weight of childRnode's exact wirelength.
     * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
     * @param rnodeDelayWeight The weight of childRnode's exact delay.
     * @param rnodeEstDlyWeight The weight of estimated delay to the target.
     * @param queue The priority queue of routenodes to expand.
     * @param targets The list of all sink routenodes.
     */
    protected void prepareRouteConnection(Connection connectionToRoute, float shareWeight, float rnodeCostWeight,
                                          float rnodeLengthWeight, float rnodeEstWlWeight,
                                          float rnodeDelayWeight, float rnodeEstDlyWeight, PriorityQueue<RouteNode> queue,
										  ArrayList<RouteNode> targets) {
        // Rips up the connection
        // ripUp(connectionToRoute);

		lock.lock();
        connectionsRouted++;
        connectionsRoutedIteration++;
		lock.unlock();
        assert(queue.isEmpty());

        // Sets the sink rnode(s) of the connection as the target(s)
        // connectionToRoute.setAllTargets(true, targets);
        connectionToRoute.setAllTargets(true);
        RouteNode sinkRnode = connectionToRoute.getSinkRnode();
        NetWrapper netWrapper = connectionToRoute.getNetWrapper();
        if (sinkRnode.countConnectionsOfUser(netWrapper) == 0 ||
            sinkRnode.getIntentCode() == IntentCode.NODE_PINBOUNCE) {
            // Since this connection will have been ripped up, only mark a node
            // as a target if it's not already used by this net.
            // This prevents -- for the case where the same net needs to be routed
            // to the same LUT more than once -- the illegal case of the same
            // physical pin servicing more than one logical pin
			targets.add(sinkRnode);
        }
        for (RouteNode rnode : connectionToRoute.getAltSinkRnodes()) {
            // Same condition as above: only allow this as an alternate sink
            // if it's not already in use by the current net to prevent the case
            // where the same physical pin services more than one logical pin
            if (rnode.countConnectionsOfUser(netWrapper) == 0 ||
                // Except if it is not a PINFEED_I
                rnode.getType() != RouteNodeType.PINFEED_I) {
                assert(rnode.getIntentCode() != IntentCode.NODE_PINBOUNCE);
                rnode.setTarget(true);
                targets.add(rnode);
            }
        }

        // Adds the source rnode to the queue
        RouteNode sourceRnode = connectionToRoute.getSourceRnode();
        assert(sourceRnode.getPrev() == null);
		int connectionId = connectionIdBase + connectionToRoute.hashCode();
        push(sourceRnode, 0, 0, queue, connectionId);
    }

    @Override
    /**
     * Initializes routing.
     */
    protected void initializeRouting() {
        super.initializeRouting();
        partitionTree = new PartitionTree(sortedIndirectConnections, design.getDevice().getColumns(), design.getDevice().getRows());
		lock = new ReentrantLock();
		ParallelismTools.setParallel(true);
    }

    public static <T> void joinBlockingQueue(BlockingQueue<? extends Future<? extends T>> futures) {
        while (!futures.isEmpty()) {
            Future<?> f = futures.poll();
            ParallelismTools.get(f);
        }
    }

    
    @Override
    /**
     * Routes a connection.
     * @param connection The connection to route.
     */
    protected void routeConnection(Connection connection) {
        float rnodeCostWeight = 1 - connection.getCriticality();
        float shareWeight = (float) (Math.pow(rnodeCostWeight, config.getShareExponent()));
        float rnodeWLWeight = rnodeCostWeight * oneMinusWlWeight;
        float estWlWeight = rnodeCostWeight * wlWeight;
        float dlyWeight = connection.getCriticality() * oneMinusTimingWeight / 100f;
        float estDlyWeight = connection.getCriticality() * timingWeight;

    	/** The queue to store candidate nodes to route a connection */
    	PriorityQueue<RouteNode> queue = new PriorityQueue<>();
		ArrayList<RouteNode> targets = new ArrayList<>();
        prepareRouteConnection(connection, shareWeight, rnodeCostWeight,
                rnodeWLWeight, estWlWeight, dlyWeight, estDlyWeight, queue, targets);

        int nodesPoppedThisConnection = 0;
        RouteNode rnode;
        while ((rnode = queue.poll()) != null) {
            nodesPoppedThisConnection++;
            if (rnode.isTarget()) {
                if (rnode != connection.getSinkRnode())
                    System.out.println("Conn " + connection.hashCode() + " find a different target: " + rnode.toString() + " , Conn Sink: " + connection.getSinkRnode().toString());
                break;
            }
            exploreAndExpand(rnode, connection, shareWeight, rnodeCostWeight,
                    rnodeWLWeight, estWlWeight, dlyWeight, estDlyWeight, queue);
        }
        // nodesPushed += nodesPoppedThisConnection + queue.size();
        // nodesPopped += nodesPoppedThisConnection;

        if (rnode != null) {
            queue.clear();
            finishRouteConnection(connection, rnode);
            if (!connection.getSink().isRouted()) {
                throw new RuntimeException("Unable to save routing for connection " + connection);
            }
            if (config.isTimingDriven()) connection.updateRouteDelay();
            assert(connection.getSink().isRouted());
        } else {
            System.out.println("Conn " + connection.hashCode() + "cannot find targets");
            assert(queue.isEmpty());
            // Clears previous route of the connection
            connection.resetRoute();
            assert(connection.getRnodes().isEmpty());
            assert(!connection.getSink().isRouted());

            if (connection.getAltSinkRnodes().isEmpty()) {
                // Undo what ripUp() did for this connection which has a single exclusive sink
                RouteNode sinkRnode = connection.getSinkRnode();
                sinkRnode.incrementUser(connection.getNetWrapper());
                sinkRnode.updatePresentCongestionCost(presentCongestionFactor);
            }
        }

        // reset target;
		for (RouteNode node: targets)
			node.setTarget(false);
    }

    /**
     * Parallel route a partition tree (blocked).
     */
	private void routePartitionTree(PartitionTreeNode node) {
		assert(node != null);
        BlockingQueue<Future<?>> jobs = new LinkedBlockingQueue<>();
		jobs.add(ParallelismTools.submit(() -> {
			routePartitionTreeHelper(jobs, node);
		}));
		joinBlockingQueue(jobs);
	}

    /**
     * Parallel route a partition tree (unblocked).
     */
    private void routePartitionTreeHelper(BlockingQueue<Future<?>> jobs, PartitionTreeNode node) {
		/* sort connection */
		assert(node != null);
    	if (node.left == null && node.right == null) {
			assert(node.middle == null);
            for (Connection connection : node.connections) {
                if (shouldRoute(connection)) {
                    ripUp(connection);
                    routeConnection(connection);
                }
            }
        } else {
			assert(node.left != null && node.right != null);
			if (node.middle != null)
	            routePartitionTree(node.middle);

			jobs.add(ParallelismTools.submit(() -> {
				routePartitionTreeHelper(jobs, node.left);
			}));
			jobs.add(ParallelismTools.submit(() -> {
				routePartitionTreeHelper(jobs, node.right);
			}));
		} 
	}

    @Override
    protected void routeSortedOrPartitionedConnections() {
        connectionIdBase = connectionsRouted + 1;
        routePartitionTree(partitionTree.root());
    }
    // override/overload functions <-

    /**
     * Routes a {@link Design} instance.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args) {
        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        return routeDesign(design, new RWRouteConfig(args));
    }

    private static Design routeDesign(Design design, RWRouteConfig config) {
        if (!config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Not masking nodes across RCLK could result in delay optimism.");
        }
        return routeDesign(design, new CUFR(design, config));
    }

    /**
     * Routes a design after pre-processing.
     * @param design The {@link Design} instance to be routed.
     * @param router A {@link CUFR} object to be used to route the design.
     */
    protected static Design routeDesign(Design design, CUFR router) {
        router.preprocess();

        // Initialize router object
        router.initialize();

        // Routes the design
        router.route();

        return router.getDesign();
    }
    
    /**
     * The main interface of {@link CUFR} that reads in a {@link Design} design
     * (DCP or FPGA Interchange), and parses the arguments for the
     * {@link RWRouteConfig} object of the router.
     * 
     * @param args An array of strings that are used to create a
     *             {@link RWRouteConfig} object for the router.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp|input.phys> <output.dcp>");
            return;
        }
        // Reads the output directory and set the output design checkpoint file name
        String routedDCPfileName = args[1];

        CodePerfTracker t = new CodePerfTracker("RWRoute", true);

        // Reads in a design and routes it
        String[] rwrouteArgs = Arrays.copyOfRange(args, 2, args.length);
        Design input = null;
        if (Interchange.isInterchangeFile(args[0])) {
            input = Interchange.readInterchangeDesign(args[0]);
        } else {
            input = Design.readCheckpoint(args[0]);
        }
        Design routed = routeDesignWithUserDefinedArguments(input, rwrouteArgs);

        // Writes out the routed design checkpoint
        routed.writeCheckpoint(routedDCPfileName,t);
        System.out.println("\nINFO: Wrote routed design\n " + routedDCPfileName + "\n");
    }
}
