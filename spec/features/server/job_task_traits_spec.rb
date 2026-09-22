require 'spec_helper'

# Trait gating regression: tasks created through the UI "Run" path must get
# their `traits` column populated from the job spec, for BOTH spec shapes:
#   list form  -> traits: [git, Bash]
#   map form   -> traits: { Bash: true }
# Previously the UI create-job path omitted the traits column entirely
# (defaulting to {} = "matches any executor"), and the auto-trigger dropped
# list-form traits to {}. Either way trait gating silently disappeared, so a
# task requiring e.g. `incus` could be dispatched to an executor without it.
feature 'Job task traits' do

  PROJECT_ID  = 'cider-ci-demo-project'
  HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: PROJECT_ID, name: 'Demo Project', git_url: 'local')
    visit "/projects/#{PROJECT_ID}/commits/#{HEAD_COMMIT}/jobs"
  end

  # traits::text[] -> sorted array of lowercase names, independent of the
  # Sequel pg_array extension being loaded.
  def task_traits(job_key)
    job_id = database[:jobs].where(project_id: PROJECT_ID, commit_id: HEAD_COMMIT, key: job_key).get(:id)
    expect(job_id).not_to be_nil
    database.fetch("SELECT array_to_string(traits, ',') AS traits FROM tasks WHERE job_id = ?", job_id)
            .map { |r| r[:traits].to_s.split(',').sort }
  end

  scenario 'list-form traits ([git, Bash]) are stored, lowercased, on the created task' do
    find('tr', text: 'Show Infos').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    traits = task_traits('info')
    expect(traits).not_to be_empty
    traits.each { |t| expect(t).to include('bash', 'git') }
  end

  scenario 'map-form traits ({Bash: true}) are stored, lowercased, on the created task' do
    find('tr', text: 'Environment Variables Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    traits = task_traits('environment_variables')
    expect(traits).not_to be_empty
    traits.each { |t| expect(t).to include('bash') }
  end
end
