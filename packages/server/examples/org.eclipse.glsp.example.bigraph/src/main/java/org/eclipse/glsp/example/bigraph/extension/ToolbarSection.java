package org.eclipse.glsp.example.bigraph.extensions;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.glsp.server.features.toolpalette.PaletteItem;

public class ToolbarSection {

    private final String label;
    private final List<PaletteItem> items = new ArrayList<>();

    public ToolbarSection(final String label) {
        this.label = label;
    }

    public void addItem(final String id, final String itemLabel, final String icon) {
        final PaletteItem item = new PaletteItem(id, itemLabel);
        item.setIcon(icon);
        items.add(item);
    }

    public String getLabel() {
        return label;
    }

    public List<PaletteItem> getItems() {
        return items;
    }
}
