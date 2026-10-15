// =============================================================================
// Household-finance policy DAG (energy subsidy and loan defaults) - seed script
// Running example of the DEFT landscape paper (main.tex, Fig. 2). Authored by the
// paper's authors; 22 variables (3 latent), 37 edges. Variable ids and edges are
// faithful to paper/figures/policy-dag.tex.
//
// Run with: cypher-shell -u neo4j -p <password> -f dev/seed-policy-dag.cypher
// Note: like seed-dag.cypher, step 1 clears every :Variable node in the database.
// =============================================================================

// --- 1. Clear existing causal graph data & establish constraints ---
MATCH (v:Variable) DETACH DELETE v;
CREATE CONSTRAINT variable_id_unique IF NOT EXISTS FOR (v:Variable) REQUIRE v.id IS UNIQUE;

// --- 2. Create Variable nodes (holder is informative only) ---
UNWIND [
  {id: 'energy_subsidy',           name: 'Energy subsidy received',  holder: 'authority',                  observed: true , latent: false},
  {id: 'eligibility_rule',         name: 'Eligibility rule',         holder: 'authority',                  observed: true , latent: false},
  {id: 'household_size',           name: 'Household size',           holder: 'statistics office',          observed: true , latent: false},
  {id: 'urbanicity',               name: 'Urbanicity',               holder: 'statistics office',          observed: true , latent: false},
  {id: 'regional_unemployment',    name: 'Regional unemployment',    holder: 'statistics office',          observed: true , latent: false},
  {id: 'household_income',         name: 'Declared household income', holder: 'tax office',                 observed: true , latent: false},
  {id: 'energy_price',             name: 'Energy price',             holder: 'energy providers',           observed: true , latent: false},
  {id: 'energy_consumption',       name: 'Energy consumption',       holder: 'energy providers',           observed: true , latent: false},
  {id: 'energy_arrears',           name: 'Energy bill arrears',      holder: 'energy providers',           observed: true , latent: false},
  {id: 'job_loss',                 name: 'Job loss',                 holder: 'employment service',         observed: true , latent: false},
  {id: 'employment_status',        name: 'Employment status',        holder: 'employment service',         observed: true , latent: false},
  {id: 'benefit_uptake',           name: 'Benefit uptake',           holder: 'social services',            observed: true , latent: false},
  {id: 'credit_access',            name: 'Credit access',            holder: 'banks',                      observed: true , latent: false},
  {id: 'consumer_credit',          name: 'Consumer credit',          holder: 'banks',                      observed: true , latent: false},
  {id: 'savings_buffer',           name: 'Savings buffer',           holder: 'banks',                      observed: true , latent: false},
  {id: 'loan_defaults',            name: 'Loan defaults',            holder: 'banks',                      observed: true , latent: false},
  {id: 'insurance_coverage',       name: 'Insurance coverage',       holder: 'insurers',                   observed: true , latent: false},
  {id: 'insurance_claims',         name: 'Insurance claims',         holder: 'insurers',                   observed: true , latent: false},
  {id: 'health_shock',             name: 'Health shock',             holder: 'health service',             observed: true , latent: false},
  {id: 'financial_literacy',       name: 'Financial literacy',       holder: 'latent',                     observed: false, latent: true},
  {id: 'informal_income',          name: 'Informal income',          holder: 'latent',                     observed: false, latent: true},
  {id: 'local_economic_shock',     name: 'Local economic shock',     holder: 'latent',                     observed: false, latent: true}
] AS props
CREATE (:Variable {
  id:       props.id,
  name:     props.name,
  holder:   props.holder,
  observed: props.observed,
  latent:   props.latent
});

// --- 3. Create CAUSES edges ---
UNWIND [
  {from: 'eligibility_rule',       to: 'energy_subsidy'},
  {from: 'energy_price',           to: 'energy_subsidy'},
  {from: 'household_income',       to: 'energy_subsidy'},
  {from: 'benefit_uptake',         to: 'energy_subsidy'},
  {from: 'energy_subsidy',         to: 'energy_arrears'},
  {from: 'energy_subsidy',         to: 'consumer_credit'},
  {from: 'energy_price',           to: 'energy_arrears'},
  {from: 'energy_price',           to: 'energy_consumption'},
  {from: 'energy_consumption',     to: 'energy_arrears'},
  {from: 'household_size',         to: 'energy_consumption'},
  {from: 'urbanicity',             to: 'energy_consumption'},
  {from: 'household_size',         to: 'benefit_uptake'},
  {from: 'financial_literacy',     to: 'benefit_uptake'},
  {from: 'financial_literacy',     to: 'savings_buffer'},
  {from: 'financial_literacy',     to: 'insurance_coverage'},
  {from: 'household_income',       to: 'savings_buffer'},
  {from: 'household_income',       to: 'consumer_credit'},
  {from: 'household_income',       to: 'loan_defaults'},
  {from: 'employment_status',      to: 'household_income'},
  {from: 'job_loss',               to: 'employment_status'},
  {from: 'regional_unemployment',  to: 'job_loss'},
  {from: 'regional_unemployment',  to: 'loan_defaults'},
  {from: 'local_economic_shock',   to: 'job_loss'},
  {from: 'local_economic_shock',   to: 'loan_defaults'},
  {from: 'savings_buffer',         to: 'loan_defaults'},
  {from: 'energy_arrears',         to: 'loan_defaults'},
  {from: 'energy_arrears',         to: 'credit_access'},
  {from: 'urbanicity',             to: 'credit_access'},
  {from: 'credit_access',          to: 'consumer_credit'},
  {from: 'consumer_credit',        to: 'loan_defaults'},
  {from: 'informal_income',        to: 'consumer_credit'},
  {from: 'informal_income',        to: 'loan_defaults'},
  {from: 'health_shock',           to: 'insurance_claims'},
  {from: 'insurance_coverage',     to: 'insurance_claims'},
  {from: 'health_shock',           to: 'loan_defaults'},
  {from: 'insurance_coverage',     to: 'loan_defaults'},
  {from: 'health_shock',           to: 'job_loss'}
] AS edge
MATCH (a:Variable {id: edge.from}), (b:Variable {id: edge.to})
CREATE (a)-[:CAUSES {active: true}]->(b);

// --- 4. Verify (expected: 22 variables, 37 causalEdges) ---
MATCH (v:Variable) WITH count(v) AS nodes
MATCH ()-[r:CAUSES]->() WITH nodes, count(r) AS edges
RETURN nodes AS variables, edges AS causalEdges;
