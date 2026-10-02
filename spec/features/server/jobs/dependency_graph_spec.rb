require 'spec_helper'

# The job dependency graph on the commit's jobs page: ELK layered layout with
# edge labels (dependency / trigger details), multi-line node labels, a
# fullscreen zoom/pan overlay and a standalone natural-size page.
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

  def view_box_width(selector)
    page.evaluate_script("document.querySelector(#{selector.to_json}).getAttribute('viewBox').split(' ')[2]").to_f
  end

  scenario 'renders nodes, dependency edge labels and wraps long job names' do
    expect(page).to have_content 'Job Dependencies'
    svg = find('svg.jobs-dag', wait: 20)

    # "job-dependent" depends on "job-prerequisite" (states: [passed]) -> one
    # labelled edge; the demo job names are long and get wrapped into tspans.
    within(svg) do
      # edge labels are wrapped into tspans ("dependency:" / "job-prerequisite-has-passed")
      expect(page).to have_css('tspan', text: 'dependency:')
      expect(page).to have_css('tspan', text: 'job-prerequisite-has-passed')
      expect(page).to have_css('tspan', text: 'states: passed')
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

  scenario 'click opens a fullscreen overlay with zoom buttons; Escape closes it' do
    find('svg.jobs-dag', wait: 20).click
    expect(page).to have_css('.jobs-dag-overlay svg.jobs-dag')

    initial = view_box_width('.jobs-dag-overlay svg.jobs-dag')
    within('.jobs-dag-overlay') { click_button '+' }
    expect(view_box_width('.jobs-dag-overlay svg.jobs-dag')).to be < initial
    within('.jobs-dag-overlay') { click_button 'fit' }
    expect(view_box_width('.jobs-dag-overlay svg.jobs-dag')).to be_within(0.01).of(initial)

    expect(page).to have_link('open in new tab', href: %r{/jobs/graph\z})
    find('body').send_keys(:escape)
    expect(page).not_to have_css('.jobs-dag-overlay')
  end

  scenario 'the standalone graph page shows the graph at natural size' do
    link = find('a', text: 'open in new tab', match: :first)
    expect(link[:target]).to eq '_blank'
    visit link[:href]

    expect(page).to have_content 'Dependencies'
    svg = find('svg.jobs-dag', wait: 20)
    within(svg) { expect(page).to have_css('tspan', text: 'job-prerequisite-has-passed') }
    # natural size: rendered width equals the viewBox width (no max-width scaling)
    rendered = page.evaluate_script("document.querySelector('svg.jobs-dag').getBoundingClientRect().width")
    expect(rendered).to be_within(1).of(view_box_width('svg.jobs-dag'))

    click_button 'fit to width'
    expect(page).to have_button 'natural size'
  end
end
