package org.eclipse.glsp.example.bigraph.model;

import java.util.Objects;

/**
 * Self-describing diagram variant that a given
 * {@link org.eclipse.glsp.example.bigraph.extensions.IdeExtension} supports.
 *
 * <p>The {@link #getId()} is the canonical discriminator persisted in the
 * {@code .bigraph-meta} file as {@code modelType}. All registration,
 * lookup, and gating uses this id — there are no hard-coded variant
 * strings elsewhere.</p>
 *
 * <p>One extension may declare more than one variant (e.g. a future
 * "petri-net" extension could ship both {@code "petri-net-elementary"}
 * and {@code "petri-net-colored"}).</p>
 *
 * <p>Immutable value object. Use {@link Builder} or the convenience
 * {@link #of} factory.</p>
 */
public final class ModelVariant {

    private final String id;
    private final String displayName;
    private final String description;
    private final String iconCodicon;

    private ModelVariant(Builder b) {
        this.id           = Objects.requireNonNull(b.id, "id");
        this.displayName  = Objects.requireNonNull(b.displayName, "displayName");
        this.description  = b.description == null ? "" : b.description;
        this.iconCodicon  = b.iconCodicon == null ? "symbol-misc" : b.iconCodicon;
    }

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /** VS Code Codicon id (without the {@code $(...)} wrapper). */
    public String getIconCodicon() { return iconCodicon; }

    @Override public boolean equals(Object o) {
        return o instanceof ModelVariant && id.equals(((ModelVariant) o).id);
    }
    @Override public int hashCode()         { return id.hashCode(); }
    @Override public String toString()      { return "ModelVariant[" + id + "]"; }

    public static Builder builder()                { return new Builder(); }
    public static ModelVariant of(String id, String displayName) {
        return builder().id(id).displayName(displayName).build();
    }

    public static final class Builder {
        private String id;
        private String displayName;
        private String description;
        private String iconCodicon;

        public Builder id(String v)          { this.id = v;          return this; }
        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder iconCodicon(String v) { this.iconCodicon = v; return this; }

        public ModelVariant build() { return new ModelVariant(this); }
    }
}
