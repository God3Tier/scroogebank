# Plans the bootstrap stack against a mocked provider and checks the budget
# alerts (ADR-0029). Run with: terraform init && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  budget_emails     = ["crm-budget@example.test"]
  budget_start_date = "2026-09-01"
}

run "budgets_count_real_spend_for_the_whole_project" {
  command = plan

  assert {
    condition     = aws_budgets_budget.total.time_unit == "ANNUALLY" && aws_budgets_budget.total.time_period_start == "2026-09-01_00:00"
    error_message = "crm-total must count the whole project from budget_start_date, not reset monthly."
  }
  assert {
    condition     = aws_budgets_budget.total.limit_amount == "300"
    error_message = "crm-total must default to the USD 300 hard limit."
  }
  assert {
    condition     = aws_budgets_budget.total.cost_types[0].include_credit == false && aws_budgets_budget.bedrock.cost_types[0].include_credit == false
    error_message = "Budgets must count cost before credits, or they never fire on a credit-covered account."
  }
  assert {
    condition     = toset([for n in aws_budgets_budget.total.notification : n.threshold if n.notification_type == "ACTUAL"]) == toset([50, 70, 85, 95, 100])
    error_message = "crm-total must alert at 50, 70, 85, 95 and 100% of actual spend (ADR-0029)."
  }
  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled" && aws_s3_bucket_versioning.trail.versioning_configuration[0].status == "Enabled"
    error_message = "Terraform state and CloudTrail logs must both retain version history."
  }
}

run "rejects_a_badly_formatted_start_date" {
  command = plan

  variables {
    budget_start_date = "1 Sep 2026"
  }

  expect_failures = [var.budget_start_date]
}
