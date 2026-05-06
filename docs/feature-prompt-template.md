# Feature Prompt Template

Save this as `PROMPT.md` in the project root. Claude reads it when you run `/spec` with no arguments, executes the task, then deletes the file.

```markdown
GOAL:
{One sentence describing the desired outcome}

CURRENT TASK:
- {Specific action item 1}
- {Specific action item 2}
- {Specific action item 3}

REFERENCES:
- {Path to relevant file or doc}
- {Path to relevant file or doc}

HINTS:
- {Implementation hint or constraint}
- {Known gotcha or edge case}
- {Preferred approach if applicable}

Implement the CURRENT TASK and nothing else.
```

## Example

```markdown
GOAL:
Add a monthly revenue breakdown chart to the finance dashboard.

CURRENT TASK:
- Create a query function that fetches revenue grouped by month for the last 12 months
- Build a bar chart component showing the monthly values
- Add the chart to the finance dashboard page below the existing KPI cards

REFERENCES:
- docs/schema.md (revenue tables)
- CLAUDE.md (unit conversion rules)

HINTS:
- Stripe amounts are in cents — divide by 100
- Use the existing chart component patterns in components/charts/
- Include a loading skeleton while data fetches

Implement the CURRENT TASK and nothing else.
```
