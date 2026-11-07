# Real-data benchmark: use cases and dataset plan

Status: plan. Nothing in this document is built yet.

## Why

`bench/synth.py` generates every trip, every utterance and every trap. The miner and the
generator were written by the same people, so a good score proves little: "you made up the
data." This plan replaces the trip layer with real driving wherever open data allows, keeps
the generator for what no public dataset records (utterances, traps, most in-car settings),
and labels every event with where it came from so the score can be read honestly.

The method is use-case first: list what carmem claims to do, then for each use case say
which real trips exhibit it, how the right answer is known without us deciding it, and which
metric scores it. Use cases become selectors over real data rather than scripts.

## Sources

| Source | What it gives | Limits | Licence / access | Role |
|---|---|---|---|---|
| **VED** (Vehicle Energy Dataset, Ann Arbor, Nov 2017 – Nov 2018) | 383 personal cars, ~1 year each, per-second GPS, speed, outside air temperature, AC and heater power; HV battery state of charge for the 27 EV/PHEV | One record per car, not per driver; no trip purpose; trip ends de-identified (random fogging, cut to the first/last major intersection, geofenced to Ann Arbor) | Apache-2.0, GitHub `gsoh/VED` | Primary trip layer; real climate and motion context |
| **NHTS 2022** (FHWA) | Per household member, per vehicle trip diaries with `VEHID`, `WHODROVE`, purpose, times | One travel day per person; coarse geography | Public domain | Household shape: drivers vs vehicles, how often a secondary driver takes the main car, trip chain frequencies |
| **PSRC Puget Sound 2017/2019** | 7-day smartphone diaries for ~20% of households, with purpose | One week only | Public data portal / NREL TSDC public tier | Optional check of purpose labels and chains |
| **MOBIS** (ETH Zurich, 2019) | 8 weeks of purpose-labelled GPS for ~3,700 people | Per person, not a shared car; access terms unconfirmed | Research release | Optional local track for purpose-aware routines; never committed |
| **German Mobility Panel (MOP)** | 1-week diary for every household member, repeated yearly | Application required | DLR Clearing House | Optional local track |

Not usable for a published benchmark: NREL TSDC secure-tier GPS (raw data cannot leave the
enclave). No open dataset of naturalistic in-car settings (seat, media, set temperature,
drive mode) was found; the AutoVis authors (CHI 2023) report the same.

## Provenance

Every event and every use case carries one of:

* `real` - the behaviour is in the source data.
* `composed` - real trips, but the arrangement is ours (two real cars merged into one shared
  car).
* `real_context` - real time, place, speed and outside temperature; the setting value is
  synthetic.
* `synthetic` - fully planted by the generator.

The scorer reports every metric split by these groups.

## Ground truth

* **Held-out future** (routines): mine on weeks 1-8 of a car's year, then check whether each
  promoted routine actually occurs in weeks 9-16 (and slide the window across the year).
  Precision is promoted routines that come true over all promoted; a false proactive is a
  proposal for a trip that did not happen. The answer is what the person really did, not a
  rule we wrote.
* **Real before/after** (changes, holidays, gaps): the split point is detected in the data
  and the expected state on each side follows from the held-out rule.
* **Composed**: we know whose trip is whose because we merged them.
* **Planted**: as in `bench/synth.py` today.

## Use cases

### Routines

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U1 | Weekday commute | Promote home→work after 3 occurrences in 3 weeks | real | Car with a home→X pair on >=70% of weekdays for >=10 weeks | Held-out future | Promotion precision/recall, days to first correct act |
| U2 | Chained stops (home→school→work) | Learn each leg, not one merged trip | real | Weekday days with >=2 legs before 10:00 and a short dwell (<15 min) at the middle place | Held-out future | Per-leg precision |
| U3 | Low-frequency weekly (Friday gym) | Promote at one trip a week | real | Place visited on the same weekday in >=6 of 10 weeks | Held-out future | Recall at 1/week |
| U4 | Irregular errands | Must not promote | real | Places with >=4 visits and no weekday/slot regularity | Held-out future (no regular repeat) | False promotion rate |
| U5 | Routine change (new job, moved gym) | Retire the old routine, learn the new one | real | Car whose top weekday destination changes and stays changed for >=4 weeks | Real before/after | Days to retire, days to learn, proposals to the old place after the change |
| U6 | Holidays and breaks | No school run at Thanksgiving, Christmas, summer | real | Weeks where a promoted routine's slot is empty across a public holiday or school break | Real calendar | False proactive rate on holidays |
| U7 | Car unused >= 2 weeks | Routine survives the gap or decays as `Policy` says | real | Gaps of >=14 days with no trips | Real gap; expected state from policy | Correct state after gap |

### Shared car and identity

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U8 | Two regular drivers | Keep routines in the right person's scope | composed | Merge two VED cars with overlapping date ranges into one car; each keeps its fob | Composed | Cross-person contamination |
| U9 | Secondary driver does the primary's trip once | Not credited to the primary | composed | Move a sample of trips between the merged drivers at the NHTS `WHODROVE` swap rate | Composed | Mis-attributed occurrences |
| U10 | Cross-person leakage in Q&A | Ben never sees Asha's places or facts | composed + synthetic | Forbidden strings per scope | Planted | QA forbidden hits |
| U11 | Guest or valet | Ephemeral scope, wiped at ignition off | synthetic on a real route | Guest trip on a real road segment, voice only | Planted | Guest residue |

### Corrections and lifecycle

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U12 | "No" to a proposal | Stop proposing quickly; correct the record | real proposal + synthetic reply | Reply "no" to the first proposal for a chosen real routine | Planted | Correction half-life |
| U13 | "Forget that", then keeps going | No resurrection | real trips + synthetic forget | Forget a real routine that continues in the data | Planted | Proposals and records after forget |

### Semantic facts and vehicle

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U14 | Stated facts (school, allergy, tolls) | Store, scope, answer | synthetic at real times | Utterances placed at real trip times, naming real place clusters | Planted | Expected facts, QA |
| U16 | Vehicle fault mentioned by two people | Promote to the shared vehicle scope | synthetic | Sensor event plus utterances | Planted | Vehicle fact present, scoped correctly |

### Climate

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U15 | Preferred cabin temperature, shifting by season | Learn per person; shift with the season | real_context | Set-point generated per person, conditioned on the real outside temperature at trip start | Planted value, real season | Expected facts incl. seasonal split |
| U17 | AC / heater habit vs outside temperature | Learn "AC on above ~24 °C outside" per car | real | Cars with populated AC/heater power; per trip: outside temp at start, AC or heater on within 5 min | Held-out future | Prediction accuracy of on/off by temperature band |
| U18 | Pre-warm / pre-cool before a routine trip | Suggest conditioning before the 7:40 winter commute | real | Promoted routine + outside temp below/above threshold + heater/AC on within the first minutes | Held-out future | Suggestion precision |
| U19 | Defrost, fan, recirculation, seat and wheel heating | Remember per person, conditioned on weather | real_context | Generated only when the real outside temperature calls for it (e.g. seat heat below 5 °C) | Planted | Expected facts |
| U20 | Two drivers, different temperatures | Restore the right person's setting on driver change | composed | U8 household with distinct set-points | Composed | Wrong-person restores |
| U21 | Passenger dual zone | Passenger setting doesn't overwrite the driver's | synthetic | Passenger-side setting events | Planted | Driver set-point unchanged |

### Seating and comfort

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U22 | Seat, mirror, steering position | Restore per driver on identification | real_context | Synthetic positions per person, restored at real driver switches (U8) | Planted | Correct restores |
| U23 | Guest adjusts the seat | Don't save; restore the owner's next time | synthetic | Guest seat change in a U11 session | Planted | Owner position after guest |
| U24 | Never adjust while moving | Seat and mirror acts only when parked | real | Real VED speed decides when an "act" is allowed | Real speed | Acts issued above 0 km/h (must be 0) |

### Media and navigation

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U25 | Station or playlist by context | Learn per person and per routine | real_context | Synthetic choices tied to real routines and times (news on weekday commute) | Planted | Expected facts |
| U26 | Volume and quiet hours | Per person; lower early morning | synthetic | Volume events by real trip hour | Planted | Expected facts |
| U27 | Saved places from routines | Offer "Work" as a saved place after promotion | real | Places promoted in U1-U3 | Held-out future | Saved-place precision |
| U28 | Motorway vs local roads | Learn observed route preference | real | Share of trip time above 80 km/h per car | Held-out future | Preference matches held-out share |
| U29 | Departure heads-up | "Leave in 10 min for school" | real | Promoted routine departure times | Held-out future | Heads-up precision, minutes early/late |

### Vehicle energy, maintenance and warnings (shared scope)

| # | Use case | carmem must | Provenance | Selector / construction | Ground truth | Metric |
|---|---|---|---|---|---|---|
| U30 | Charging habit (EV / PHEV) | Learn "charges overnight at home" | real | SoC rise between consecutive trips on VED's EV/PHEV cars, attributed to the end place of the earlier trip | Held-out future | Prediction precision |
| U31 | Refuelling habit (petrol) | Learn when and where | synthetic | VED has fuel rate, not level; refuel events generated from real cumulative distance | Planted | Expected facts |
| U32 | Maintenance due by mileage | Remind every driver, not one | real mileage + synthetic schedule | Cumulative real distance against a planted service interval | Planted | Reminder reaches all scopes |
| U33 | Warning light / tyre pressure | Vehicle fact shared by all drivers | synthetic | Sensor events (extends U16) | Planted | Vehicle fact scoped correctly |
| U34 | Drive mode, driver-assist settings | Remember per person; never change automatically | synthetic | Setting events per person | Planted | Acts issued (must be 0; tier `never`) |
| U35 | Child seat / child lock present | Sensitive fact; scoped; masked on sync | synthetic | Utterance | Planted | Scope and cloud masking |

U15 sits under Climate; the number is kept so references to the earlier list stay valid.

Count: 14 `real`, 4 `composed` (U8-U10, U20), 4 `real_context` (U15, U19, U22, U25), 13 `synthetic` (some on real routes or real mileage).

## Pipeline

1. **Load VED** weekly CSVs into per-trip summaries: start/end time, start/end coordinates,
   distance, outside temperature at start, AC or heater on within 5 minutes, share of time
   above 80 km/h, stationary time, SoC at start and end (EV/PHEV).
2. **Segment and cluster** with the head unit's own rules (`kotlin/carmem-car`, see
   `DESIGN.md`): a trip ends at ignition off or 8 minutes stationary; places are clusters;
   home is the cluster with most night dwell, work the one with most weekday daytime dwell.
   The 150 m radius will need tuning against VED's endpoint fogging; report the radius used.
3. **Profile** each car: weekly repeats per place, change points, gaps, multi-stop days,
   climate coverage.
4. **Select**: run the selectors above. Report, per use case, how many cars qualify. A use
   case with no qualifying cars is listed as uncovered, not quietly made synthetic.
5. **Compose** shared cars for U8-U10 and U20 by pairing cars with overlapping dates, with
   swap rates from NHTS 2022 `WHODROVE` / `VEHID` statistics.
6. **Overlay**: three generators, each emitting only at real trip times -
   utterances (U14, U35), traps (U11-U13, U16, U23, U33), settings (U15, U19, U21, U22,
   U25, U26, U31, U34) conditioned on the real context.
7. **Ground truth**: held-out windows for real use cases, planted answers for the rest.
8. **Export** in the existing fixture format (`fixtures/wire-schema.json`) with two
   additions: a `provenance` field on every event and a `use_cases` list on every scenario.
   Place names are neutral (`place_03`) unless an overlay names them; the scenario metadata
   records which names are ours.
9. **Score** per use case and per provenance group. The Kotlin `ConformanceTest` replays the
   exported fixtures unchanged.

## Shipping

* VED (Apache-2.0) and NHTS (public domain) allow small derived fixtures in `fixtures/`,
  with attribution. Raw VED files are downloaded by a script, not committed.
* MOBIS, MOP and PSRC tracks run locally only; nothing derived from them is committed until
  their terms are confirmed.

## Risks and open questions

* VED trip ends are fogged, so place clusters are noisier than on a real head unit. That is
  a harder test, but it may also understate the miner. Report results at two radii.
* VED cars include some fleet-like use. Filter cars with fewer than 10 active weeks or more
  than ~8 distinct trips a day on most days.
* AC/heater and SoC columns may be missing for many cars; step 3 reports coverage before
  U17, U18 and U30 are claimed as real.
* Ann Arbor has one climate and one road network. The real tracks say nothing about
  right-hand drive, hot climates or dense cities.
* No real data exists for set temperature, seat, media or drive mode. Those use cases stay
  `real_context` or `synthetic` until a pilot fleet provides logs.

## First step

Download VED, run steps 1-3 for a dozen cars, run the current miner on them, and report how
many routines are promoted and how many come true in the held-out weeks, plus which use
cases have qualifying cars.

## References

* VED: Oh, LeBlanc, Peng, "Vehicle Energy Dataset (VED), A Large-scale Dataset for Vehicle
  Energy Consumption Research", IEEE T-ITS 2020, arXiv:1905.02081; data at
  https://github.com/gsoh/VED
* NHTS 2022: https://nhts.ornl.gov/ and
  https://www.fhwa.dot.gov/policyinformation/nextgen-nhts-data.cfm
* PSRC Household Travel Survey: https://psrc.org/node/10107
* MOBIS: ETH Zurich IVT, https://www.research-collection.ethz.ch/handle/20.500.11850/498317
* German Mobility Panel: https://mobilitaetspanel.ifv.kit.edu/english/survey.php
* AutoVis (no public in-car interaction dataset): arXiv:2302.10531
