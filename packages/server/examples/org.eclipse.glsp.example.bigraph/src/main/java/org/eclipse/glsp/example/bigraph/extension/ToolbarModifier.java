package org.eclipse.glsp.example.bigraph.extensions;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.glsp.server.features.toolpalette.PaletteItem;

public class ToolbarModifier {

    private final List<PaletteItem> items = new ArrayList<>();

    public void addItem(final PaletteItem item) {
        items.add(item);
    }

    public void addSection(final ToolbarSection section) {
        items.add(PaletteItem.createPaletteGroup(
            "toolbar-section-" + section.getLabel().toLowerCase(),
            section.getLabel(),
            section.getItems()));
    }

    public void addSectionslessItem(final String id, final String label, final String icon) {
        final PaletteItem item = new PaletteItem(id, label);
        item.setIcon(icon);
        items.add(item);
    }

    public List<PaletteItem> getItems() {
        return items;
    }
}
