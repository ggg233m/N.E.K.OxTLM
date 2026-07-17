package com.neko_tlm_bridge.tlm.agent.world;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure selection logic for returning over a loop-erased mining breadcrumb
 * route. Live path reachability and terrain repair remain the action's job.
 */
public final class MiningReturnRoutePlanner {
    private MiningReturnRoutePlanner() {
    }

    public static Optional<ReturnRoute> planToEntry(
            MiningWorldModelSavedData.OperationSnapshot operation,
            BlockPos currentPosition,
            double maximumAttachmentDistance) {
        Objects.requireNonNull(operation, "operation");
        return planToEntry(operation.routeBreadcrumbs(), currentPosition,
                maximumAttachmentDistance);
    }

    /**
     * Chooses the nearest recorded route cell, then returns the ordered reverse
     * waypoints from that attachment cell to the entrance.
     */
    public static Optional<ReturnRoute> planToEntry(
            List<BlockPos> breadcrumbs,
            BlockPos currentPosition,
            double maximumAttachmentDistance) {
        Objects.requireNonNull(breadcrumbs, "breadcrumbs");
        BlockPos current = Objects.requireNonNull(
                currentPosition, "currentPosition").immutable();
        if (!Double.isFinite(maximumAttachmentDistance)
                || maximumAttachmentDistance < 0.0D) {
            throw new IllegalArgumentException(
                    "maximumAttachmentDistance must be finite and non-negative");
        }
        List<BlockPos> route = validateRoute(breadcrumbs);
        if (route.isEmpty()) {
            return Optional.empty();
        }

        int attachmentIndex = java.util.stream.IntStream.range(0, route.size())
                .boxed()
                .min(Comparator
                        .comparingDouble((Integer index) ->
                                route.get(index).distSqr(current))
                        .thenComparingInt(Integer::intValue))
                .orElseThrow();
        BlockPos attachment = route.get(attachmentIndex);
        double attachmentDistance = Math.sqrt(attachment.distSqr(current));
        if (attachmentDistance > maximumAttachmentDistance) {
            return Optional.empty();
        }

        List<BlockPos> returnWaypoints = new ArrayList<>(attachmentIndex + 1);
        for (int index = attachmentIndex; index >= 0; index--) {
            returnWaypoints.add(route.get(index));
        }
        return Optional.of(new ReturnRoute(
                current, attachment, route.getFirst(), returnWaypoints,
                attachmentIndex, attachmentDistance));
    }

    private static List<BlockPos> validateRoute(List<BlockPos> breadcrumbs) {
        List<BlockPos> route = new ArrayList<>(breadcrumbs.size());
        for (BlockPos value : breadcrumbs) {
            BlockPos next = Objects.requireNonNull(
                    value, "breadcrumb").immutable();
            if (route.isEmpty()) {
                route.add(next);
                continue;
            }
            BlockPos current = route.getLast();
            if (current.equals(next)
                    || !MiningWorldModelSavedData
                    .isPlayerWalkableTransition(current, next)) {
                return List.of();
            }
            route.add(next);
        }
        return List.copyOf(route);
    }

    public record ReturnRoute(
            BlockPos currentPosition,
            BlockPos attachment,
            BlockPos entry,
            List<BlockPos> waypoints,
            int recordedStepsToEntry,
            double attachmentDistance) {
        public ReturnRoute {
            currentPosition = Objects.requireNonNull(
                    currentPosition, "currentPosition").immutable();
            attachment = Objects.requireNonNull(
                    attachment, "attachment").immutable();
            entry = Objects.requireNonNull(entry, "entry").immutable();
            waypoints = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
            if (waypoints.isEmpty()
                    || !waypoints.getFirst().equals(attachment)
                    || !waypoints.getLast().equals(entry)) {
                throw new IllegalArgumentException(
                        "waypoints must run from attachment to entry");
            }
            if (recordedStepsToEntry < 0) {
                throw new IllegalArgumentException(
                        "recordedStepsToEntry must be non-negative");
            }
            if (!Double.isFinite(attachmentDistance)
                    || attachmentDistance < 0.0D) {
                throw new IllegalArgumentException(
                        "attachmentDistance must be finite and non-negative");
            }
        }
    }
}
