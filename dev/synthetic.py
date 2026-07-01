# /// script
# dependencies = [
#   "dowhy",
#   "networkx",
#   "pandas",
#   "scikit-learn",
#   "matplotlib",
# ]
# ///

import pandas as pd
import networkx as nx
import dowhy.datasets

config1 = {
    'num_common_causes': 4,
    'num_instruments': 2,
    'num_effect_modifiers': 1,
    'num_treatments': 1,
    'num_frontdoor_variables': 1
}
config2 = {
    'num_common_causes': 5,
    'num_instruments': 2,
    'num_effect_modifiers': 5,
    'num_treatments': 8,
    'num_frontdoor_variables': 8
}
config3 = {
    'num_common_causes': 10,
    'num_instruments': 2,
    'num_effect_modifiers': 10,
    'num_treatments': 18,
    'num_frontdoor_variables': 10
}

CONFIGS = [config1, config2, config3]



for idx, config in enumerate(CONFIGS):
    for num_samples in [1_000, 10_000, 100_000]:
        # 1. Generate the synthetic causal dataset
        data = dowhy.datasets.linear_dataset( 10, 
            num_common_causes=config['num_common_causes'], 
            num_samples=num_samples,
            num_instruments=config['num_instruments'], 
            num_effect_modifiers=config['num_effect_modifiers'],
            num_treatments=config['num_treatments'],
            num_frontdoor_variables=config['num_frontdoor_variables'],
            treatment_is_binary=False,
            outcome_is_binary=False
        )

        # 2. Extract and parse the GML graph string using networkx
        gml_string = data["gml_graph"]
        graph = nx.parse_gml(gml_string)

        # 3. Create the Edges (Relationships) DataFrame
        edges_list = []
        for source, target in graph.edges():
            edges_list.append({
                    "source": source,
                    "target": target,
                    "type": "CAUSES"
                })

        edges_df = pd.DataFrame(edges_list)

        # 4. Create the Nodes DataFrame
        nodes_list = []
        for node in graph.nodes():
            # Identify the type of node based on naming patterns or dictionary keys
            if node in data["treatment_name"]:
                node_type = "Treatment"
            elif node in data["outcome_name"]:
                node_type = "Outcome"
            elif node.startswith("W"):
                node_type = "Confounder"
            elif node.startswith("Z"):
                node_type = "Instrument"
            else:
                node_type = "Variable"

            nodes_list.append({
                "nodeId": node,
                "name": node,
                "label": node_type
            })

        nodes_df = pd.DataFrame(nodes_list)

        i = idx + 1
        samples = num_samples // 1_000
        # 5. Save to CSV files
        nodes_df.to_csv(f"causal_nodes{i}_{samples}K.csv", index=False)
        edges_df.to_csv(f"causal_edges{i}_{samples}K.csv", index=False)
        print(f"{i} with {samples}K")

print("CSV files generated successfully!")
