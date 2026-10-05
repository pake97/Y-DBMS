// =============================================================================
// COVID-19 Causal DAG — seed script
// Source: Piccininni et al. (2020) causal DAG for COVID-19 spread
//
// Run with: ./dev/seed.sh
// Or manually: cypher-shell -u neo4j -p <password> -f dev/seed-dag.cypher
// =============================================================================

// --- 1. Clear existing causal graph data & establish constraints ---
MATCH (v:Variable) DETACH DELETE v;
CREATE CONSTRAINT variable_id_unique IF NOT EXISTS FOR (v:Variable) REQUIRE v.id IS UNIQUE;

// --- 2. Create Variable nodes ---
UNWIND [
  {id: 'interventions',          name: 'Interventions',                  observed: true,  latent: false},
  {id: 'covid19_burden',         name: 'COVID-19 burden',                observed: true,  latent: false},
  {id: 'healthcare_utilization', name: 'Health care utilization',        observed: true,  latent: false},
  {id: 'searches_corona',        name: 'Searches corona',                observed: true,  latent: false},
  {id: 'gender',                 name: 'Gender',                         observed: true,  latent: false},
  {id: 'age',                    name: 'Age',                            observed: true,  latent: false},
  {id: 'foreign_citizens',       name: 'Foreign citizens',               observed: true,  latent: false},
  {id: 'socioeconomic_status',   name: 'Socio-economic status',          observed: true,  latent: false},
  {id: 'turnout',                name: 'Turnout',                        observed: true,  latent: false},
  {id: 'rightwing_votes',        name: 'Right-wing populist party votes',observed: true,  latent: false},
  {id: 'trust_society',          name: 'Trust in society',               observed: true,  latent: false},
  {id: 'population_density',     name: 'Population density',             observed: true,  latent: false},
  {id: 'nursing_homes',          name: 'Nursing homes',                  observed: true,  latent: false},
  {id: 'mobility',               name: 'Mobility',                       observed: true,  latent: false},
  {id: 'temperature',            name: 'Temperature',                    observed: true,  latent: false},
  {id: 'humidity',               name: 'Humidity',                       observed: true,  latent: false},
  {id: 'wind',                   name: 'Wind',                           observed: true,  latent: false},
  {id: 'rainfall',               name: 'Rainfall',                       observed: true,  latent: false},
  {id: 'tracing_capacity',       name: 'Tracing capacity',               observed: true,  latent: false},
  {id: 'exposure_sarscov2',      name: 'Exposure to SARS-COV-2',         observed: false, latent: true},
  {id: 'herd_immunity',          name: 'Herd immunity',                  observed: false, latent: true},
  {id: 'access_tests',           name: 'Access to tests',                observed: true,  latent: false},
  {id: 'susceptibility',         name: 'Susceptibility',                 observed: false, latent: true},
  {id: 'onset_covid19',          name: 'Onset of COVID-19',              observed: false, latent: true},
  {id: 'reported_cases',         name: 'Reported new cases COVID-19',    observed: true,  latent: false}
] AS props
CREATE (:Variable {
  id:       props.id,
  name:     props.name,
  observed: props.observed,
  latent:   props.latent
});

// --- 3. Create CAUSES edges ---
UNWIND [
  {from: 'access_tests',          to: 'onset_covid19'},
  {from: 'interventions',         to: 'exposure_sarscov2'},
  {from: 'interventions',         to: 'searches_corona'},
  {from: 'interventions',         to: 'mobility'},
  {from: 'covid19_burden',        to: 'interventions'},
  {from: 'covid19_burden',        to: 'exposure_sarscov2'},
  {from: 'covid19_burden',        to: 'searches_corona'},
  {from: 'covid19_burden',        to: 'mobility'},
  {from: 'covid19_burden',        to: 'healthcare_utilization'},
  {from: 'covid19_burden',        to: 'tracing_capacity'},
  {from: 'healthcare_utilization',to: 'exposure_sarscov2'},
  {from: 'healthcare_utilization',to: 'tracing_capacity'},
  {from: 'exposure_sarscov2',     to: 'access_tests'},
  {from: 'exposure_sarscov2',     to: 'onset_covid19'},
  {from: 'foreign_citizens',      to: 'nursing_homes'},
  {from: 'foreign_citizens',      to: 'rightwing_votes'},
  {from: 'foreign_citizens',      to: 'socioeconomic_status'},
  {from: 'foreign_citizens',      to: 'age'},
  {from: 'foreign_citizens',      to: 'healthcare_utilization'},
  {from: 'herd_immunity',         to: 'exposure_sarscov2'},
  {from: 'herd_immunity',         to: 'susceptibility'},
  {from: 'nursing_homes',         to: 'exposure_sarscov2'},
  {from: 'onset_covid19',         to: 'reported_cases'},
  {from: 'population_density',    to: 'exposure_sarscov2'},
  {from: 'population_density',    to: 'rightwing_votes'},
  {from: 'population_density',    to: 'trust_society'},
  {from: 'population_density',    to: 'mobility'},
  {from: 'population_density',    to: 'healthcare_utilization'},
  {from: 'rightwing_votes',       to: 'exposure_sarscov2'},
  {from: 'rightwing_votes',       to: 'searches_corona'},
  {from: 'searches_corona',       to: 'exposure_sarscov2'},
  {from: 'searches_corona',       to: 'mobility'},
  {from: 'searches_corona',       to: 'healthcare_utilization'},
  {from: 'socioeconomic_status',  to: 'exposure_sarscov2'},
  {from: 'socioeconomic_status',  to: 'nursing_homes'},
  {from: 'socioeconomic_status',  to: 'rightwing_votes'},
  {from: 'socioeconomic_status',  to: 'searches_corona'},
  {from: 'socioeconomic_status',  to: 'trust_society'},
  {from: 'socioeconomic_status',  to: 'mobility'},
  {from: 'socioeconomic_status',  to: 'healthcare_utilization'},
  {from: 'socioeconomic_status',  to: 'turnout'},
  {from: 'tracing_capacity',      to: 'exposure_sarscov2'},
  {from: 'trust_society',         to: 'rightwing_votes'},
  {from: 'trust_society',         to: 'searches_corona'},
  {from: 'trust_society',         to: 'turnout'},
  {from: 'age',                   to: 'exposure_sarscov2'},
  {from: 'age',                   to: 'nursing_homes'},
  {from: 'age',                   to: 'onset_covid19'},
  {from: 'age',                   to: 'rightwing_votes'},
  {from: 'age',                   to: 'searches_corona'},
  {from: 'age',                   to: 'socioeconomic_status'},
  {from: 'age',                   to: 'mobility'},
  {from: 'age',                   to: 'healthcare_utilization'},
  {from: 'age',                   to: 'turnout'},
  {from: 'humidity',              to: 'exposure_sarscov2'},
  {from: 'mobility',              to: 'exposure_sarscov2'},
  {from: 'mobility',              to: 'tracing_capacity'},
  {from: 'rainfall',              to: 'exposure_sarscov2'},
  {from: 'rainfall',              to: 'humidity'},
  {from: 'rainfall',              to: 'mobility'},
  {from: 'rainfall',              to: 'temperature'},
  {from: 'gender',                to: 'exposure_sarscov2'},
  {from: 'gender',                to: 'onset_covid19'},
  {from: 'gender',                to: 'rightwing_votes'},
  {from: 'gender',                to: 'searches_corona'},
  {from: 'gender',                to: 'socioeconomic_status'},
  {from: 'gender',                to: 'mobility'},
  {from: 'gender',                to: 'healthcare_utilization'},
  {from: 'gender',                to: 'age'},
  {from: 'gender',                to: 'turnout'},
  {from: 'susceptibility',        to: 'onset_covid19'},
  {from: 'temperature',           to: 'exposure_sarscov2'},
  {from: 'temperature',           to: 'humidity'},
  {from: 'temperature',           to: 'mobility'},
  {from: 'turnout',               to: 'rightwing_votes'},
  {from: 'turnout',               to: 'searches_corona'},
  {from: 'wind',                  to: 'exposure_sarscov2'},
  {from: 'wind',                  to: 'temperature'}
] AS edge
MATCH (a:Variable {id: edge.from}), (b:Variable {id: edge.to})
CREATE (a)-[:CAUSES {active: true}]->(b);

// --- 4. Verify ---
MATCH (v:Variable) WITH count(v) AS nodes
MATCH ()-[r:CAUSES]->() WITH nodes, count(r) AS edges
RETURN nodes AS variables, edges AS causalEdges;
