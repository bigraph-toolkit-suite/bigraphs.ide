/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { GModelElement, GModelRoot, GNode, Point } from '@eclipse-glsp/client';

const HIT_EPSILON = 2;

/**
 * Finds the smallest eligible {@link GNode} at {@code point} (canvas coords).
 * Nested children (e.g. ports) are traversed; only types in {@code eligibleTypes}
 * are considered.
 */
export function findElementAt(
    root: Readonly<GModelRoot>,
    point: Point,
    eligibleTypes: ReadonlySet<string>
): GNode | undefined {
    if (eligibleTypes.size === 0) {
        return undefined;
    }

    let bestMatch: GNode | undefined = undefined;
    let smallestArea = Infinity;

    const stack: [GModelElement, number, number][] = (root.children || []).map(c => [c, 0, 0]);

    while (stack.length > 0) {
        const [child, parentOffsetX, parentOffsetY] = stack.pop()!;
        const candidate = child as GNode & {
            position?: { x?: number; y?: number };
            size?: { width?: number; height?: number };
            type?: string;
            children?: GModelElement[];
        };

        if (!candidate.position) {
            continue;
        }

        const relX = candidate.position.x ?? 0;
        const relY = candidate.position.y ?? 0;
        const absX = parentOffsetX + relX;
        const absY = parentOffsetY + relY;
        const w = candidate.size?.width ?? 0;
        const h = candidate.size?.height ?? 0;

        const inBounds =
            candidate.size &&
            point.x >= absX - HIT_EPSILON &&
            point.x <= absX + w + HIT_EPSILON &&
            point.y >= absY - HIT_EPSILON &&
            point.y <= absY + h + HIT_EPSILON;

        if (inBounds && eligibleTypes.has(String(candidate.type))) {
            const area = w * h;
            if (area < smallestArea) {
                smallestArea = area;
                bestMatch = candidate;
            }
        }

        if (candidate.children?.length) {
            for (const nested of candidate.children) {
                stack.push([nested, absX, absY]);
            }
        }
    }

    return bestMatch;
}

/** Converts a client click position to diagram-local coordinates. */
export function diagramLocalPoint(root: Readonly<GModelRoot>, clientPoint: Point): Point {
    const rootWithTransform = root as GModelRoot & {
        parentToLocal?: (point: Point) => Point;
    };
    return rootWithTransform.parentToLocal
        ? rootWithTransform.parentToLocal(clientPoint)
        : clientPoint;
}
