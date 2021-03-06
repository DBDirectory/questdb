import React, { useCallback, useState } from "react"
import { CSSTransition } from "react-transition-group"
import styled from "styled-components"

import { collapseTransition, Text, TransitionDuration } from "components"
import { color } from "utils"
import { Timings } from "utils/questdb"

type Props = Timings &
  Readonly<{
    count: number
    rowCount: number
  }>

const Wrapper = styled.div`
  display: flex;
  flex-direction: column;
  margin-top: 0.2rem;
  overflow: hidden;
  ${collapseTransition};

  svg {
    margin-right: 0.2rem;
    color: ${color("draculaForeground")};
  }
`

const Details = styled.div`
  display: flex;
  margin-top: 0.5rem;
  padding: 1rem;
  user-select: none;
  background: ${color("draculaBackground")};
`

const DetailsLink = styled(Text)`
  &:hover {
    cursor: pointer;
  }
`

const Column = styled.div`
  display: flex;
  flex-direction: column;
`

const ColumnLeft = styled(Column)`
  flex: 1;
`

const roundTiming = (time: number): number =>
  Math.round((time + Number.EPSILON) * 100) / 100

const addColor = (timing: string) => {
  if (timing === "0") {
    return <Text color="gray2">0</Text>
  }

  return <Text color="draculaOrange">{timing}</Text>
}

const formatTiming = (nanos: number) => {
  if (nanos === 0) {
    return "0"
  }

  if (nanos > 1e9) {
    return `${roundTiming(nanos / 1e9)}s`
  }

  if (nanos > 1e6) {
    return `${roundTiming(nanos / 1e6)}ms`
  }

  if (nanos > 1e3) {
    return `${roundTiming(nanos / 1e3)}μs`
  }

  return `${nanos}ns`
}

const QueryResult = ({ compiler, count, execute, fetch, rowCount }: Props) => {
  const [expanded, setExpanded] = useState(true)
  const handleClick = useCallback(() => {
    setExpanded(!expanded)
  }, [expanded])

  return (
    <Wrapper _height={95} duration={TransitionDuration.FAST}>
      <div>
        <Text color="draculaForeground">
          {rowCount.toLocaleString()} row{rowCount > 1 ? "s" : ""} in&nbsp;
          {formatTiming(fetch)}&nbsp;
        </Text>
        (
        <DetailsLink
          color="draculaForeground"
          onClick={handleClick}
          weight={800}
        >
          {expanded ? "hide" : "show"} details
        </DetailsLink>
        )
      </div>

      <CSSTransition
        classNames="collapse"
        in={expanded}
        timeout={TransitionDuration.FAST}
        unmountOnExit
      >
        <Details>
          <ColumnLeft>
            <Text color="draculaForeground">
              Execute: {addColor(formatTiming(execute))}&nbsp;
            </Text>
            <Text color="draculaForeground">
              Network:&nbsp;
              {addColor(formatTiming(fetch - execute))}
            </Text>
            <Text color="draculaForeground">
              Total:&nbsp;
              {addColor(formatTiming(fetch))}
            </Text>
          </ColumnLeft>

          <Column>
            <Text align="right" color="gray2" size="sm">
              Count: {formatTiming(count)}
            </Text>
            <Text align="right" color="gray2" size="sm">
              Compile: {formatTiming(compiler)}
            </Text>
          </Column>
        </Details>
      </CSSTransition>
    </Wrapper>
  )
}

export default QueryResult
