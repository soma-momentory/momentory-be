# Development workflow

## Branch flow

```text
feat/* -> dev -> main
```

- Create feature branches from the latest `dev` branch.
- Open feature pull requests against `dev` and use squash merge.
- A push to `dev` runs CI and deploys the development ECS service.
- Promote a tested release through a `dev` to `main` pull request.
- A push to `main` runs CI and deploys the production ECS service.
- Do not push directly to `dev` or `main`.

## Start a feature

```bash
git fetch origin
git switch dev
git pull --ff-only origin dev
git switch -c feat/feature-name
```

Before opening the pull request, update the feature branch against the latest
`dev` branch and run the checks.

```bash
git fetch origin
git rebase origin/dev
./gradlew clean check
git push -u origin feat/feature-name
```

## Deployment endpoints

- Development: `https://dev.momentory.co.kr`
- Production: `https://api.momentory.co.kr`
- Health check: `/actuator/health`

The development and production services use separate ECS services, task
definitions, Parameter Store paths, database credentials, JWT secrets, and log
groups.
