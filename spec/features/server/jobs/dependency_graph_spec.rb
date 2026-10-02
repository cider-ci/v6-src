require 'spec_helper'

# The job dependency graph on the commit's jobs page: ELK layered layout with
# edge labels (dependency / trigger details) and multi-line node labels.
feature 'Job dependency graph' do

  GRAPH_PROJECT_ID  = 'cider-ci-demo-project'
  GRAPH_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: GRAPH_PROJECT_ID, name: 'Demo Project', git_url: 'local',
                                   branch_trigger_include_match: '^__none__$') # no auto-triggered demo jobs
    visit "/projects/#{GRAPH_PROJECT_ID}/commits/#{GRAPH_HEAD_COMMIT}/jobs"
  end

  scenario 'renders nodes, dependency edge labels and wraps long job names' do
    expect(page).to have_content 'Job Dependencies'
    svg = find('svg.jobs-dag', wait: 20)

    # "job-dependent" depends on "job-prerequisite" (states: [passed]) -> one
    # labelled edge; the demo job names are long and get wrapped into tspans.
    within(svg) do
      expect(page).to have_css('text', text: 'dependency: job-prerequisite-has-passed')
      expect(page).to have_css('text', text: 'states: passed')
      expect(page).to have_css('tspan', text: 'Jobs - Dependencies and')
      expect(page).to have_css('path[marker-end]')
    end

    # Nothing is drawn outside the SVG's own box (the old layout routed long
    # edges above the graph and cut them off).
    out_of_bounds = page.evaluate_script(<<~JS)
      (function () {
        var svg = document.querySelector('svg.jobs-dag');
        var box = svg.getBoundingClientRect();
        return Array.prototype.filter.call(svg.querySelectorAll('rect, path, text'), function (el) {
          var r = el.getBoundingClientRect();
          return r.top < box.top - 1 || r.left < box.left - 1 ||
                 r.bottom > box.bottom + 1 || r.right > box.right + 1;
        }).length;
      })()
    JS
    expect(out_of_bounds).to eq 0
  end
end
