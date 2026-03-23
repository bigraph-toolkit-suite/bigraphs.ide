# Bigraph GLSP Example

A comprehensive GLSP server implementation for modeling Bigraphs with an integrated Node Explorer.

## Overview

This example demonstrates how to create a GLSP-based editor for bigraph modeling that includes:

- **Node Explorer**: A side panel for managing custom node types
- **Bigraph Framework Integration**: Uses the bigraph-toolkit-suite for backend modeling
- **Custom Node Creation**: Users can define their own node types with properties
- **Bigraph Theory Support**: Implements core bigraph concepts (nodes, edges, sites, regions)

## Features

### 🎯 Core Bigraph Elements
- **Atomic Nodes**: Leaf nodes that cannot contain other elements
- **Container Nodes**: Nodes that can contain other bigraph elements
- **Sites**: Placeholders for bigraph composition
- **Links/Hyperedges**: Connections between nodes and ports
- **Regions**: Organizational containers

### 🔧 Node Explorer
- **Toolbar Integration**: Click the "Node Explorer" button in the GLSP toolbar
- **Custom Node Creation**: Add new node types with:
  - Name and unique identifier
  - Arity (number of ports)
  - Atomic/Container classification
  - Color and icon customization
  - Description text
- **Predefined Types**: Access to standard bigraph node types
- **Real-time Updates**: Changes immediately available in the palette

### 🎨 Visual Modeling
- **Drag & Drop**: Create nodes by dragging from the palette
- **Hierarchical Structure**: Nest nodes within containers
- **Port Management**: Visual representation of node ports
- **Link Creation**: Connect nodes via links and hyperedges

## Architecture

### Server Components

```
BigraphDiagramModule
├── BigraphDiagramConfiguration    # Type mappings and hints
├── NodeExplorerActionHandler      # Handles Node Explorer actions
├── BigraphToolbarProvider         # Provides toolbar items
└── Model Classes
    ├── BigraphModelTypes          # Type definitions
    └── CustomNodeDefinition       # Custom node data model
```

### Key Actions

- `OpenNodeExplorerAction`: Opens/toggles the Node Explorer panel
- `RequestNodeTypesAction`: Requests available node types
- `AddCustomNodeAction`: Adds a new custom node definition
- `NodeTypesResponseAction`: Returns available node types to client

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.6+
- GLSP VSCode Extension (for client)

### Building

```bash
cd examples/org.eclipse.glsp.example.bigraph
mvn clean package
```

### Running the Server

```bash
java -jar target/org.eclipse.glsp.example.bigraph-2.0.0.jar --port=5007
```

Or use the provided launch configuration in your IDE.

### Connecting from VSCode

1. Install the GLSP VSCode extension
2. Configure the extension to connect to `localhost:5007`
3. Open a `.bigraph` file
4. The bigraph editor will load with the Node Explorer available

## Usage Guide

### Creating Custom Nodes

1. **Open Node Explorer**: Click the "Node Explorer" button in the toolbar
2. **Add New Node**: Click the "+" button in the Node Explorer panel
3. **Configure Properties**:
   - **Name**: Display name for the node type
   - **ID**: Unique identifier (auto-generated from name)
   - **Arity**: Number of ports (0 for no ports)
   - **Type**: Atomic (leaf) or Container (can hold other nodes)
   - **Color**: Visual appearance color
   - **Description**: Optional description text

4. **Save**: The new node type immediately appears in the palette

### Building Bigraphs

1. **Create Root**: Start with a root container
2. **Add Regions**: Create organizational regions within the root
3. **Place Nodes**: Drag nodes from the palette into regions/containers
4. **Create Links**: Connect node ports using link tools
5. **Add Sites**: Place sites for composition points

### Example Bigraph Structure

```
Root
├── Region 1
│   ├── Computer (arity: 2)
│   │   ├── Port 1 → Network Link
│   │   └── Port 2 → Power Link
│   └── Printer (arity: 1)
│       └── Port 1 → Network Link
└── Region 2
    ├── Room Container
    │   ├── Site (for future expansion)
    │   └── Custom Node: "Sensor"
    └── Network Link (hyperedge)
```

## Integration with Bigraph Framework

The server integrates with the bigraph-toolkit-suite to provide:

- **Signature Management**: Custom nodes become bigraph controls
- **Model Validation**: Ensures bigraph theory compliance
- **Export Capabilities**: Generate bigraph models for analysis
- **Composition Operations**: Support for bigraph algebra

## Extending the Implementation

### Adding New Node Properties

1. Extend `CustomNodeDefinition` with new fields
2. Update the Node Explorer UI to include new property editors
3. Modify `NodeExplorerActionHandler` to handle new properties

### Custom Edge Types

1. Add new edge types to `BigraphModelTypes`
2. Create corresponding action handlers
3. Update `BigraphDiagramConfiguration` with edge hints
4. Add tools to `BigraphToolbarProvider`

### Persistence

Currently, custom nodes are stored in memory. To add persistence:

1. Implement a `CustomNodeRepository` interface
2. Add database or file-based storage
3. Update `NodeExplorerActionHandler` to use the repository
4. Add loading/saving actions

## Troubleshooting

### Common Issues

1. **Server Won't Start**: Check Java version (requires 17+)
2. **Node Explorer Not Visible**: Ensure toolbar provider is properly bound
3. **Custom Nodes Not Saving**: Check action handler registration
4. **Connection Issues**: Verify port configuration matches client

### Debug Mode

Run with debug logging:
```bash
java -Dlog4j.configuration=log4j.properties -jar target/bigraph-server.jar
```

## Contributing

This implementation provides a solid foundation for bigraph modeling in GLSP. Areas for contribution:

- Enhanced Node Explorer UI
- Bigraph validation rules
- Export to various formats
- Collaborative editing features
- Performance optimizations

## License

This project follows the same license as the GLSP framework (EPL-2.0). 